package queue

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

type otzivRequest struct{ id int }

func (*otzivRequest) Weight() int { return 1 }

func otzivQueue() *RequestQueue {
	return NewRequestQueue(1000,
		prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "otziv_queue_length"}, []string{"user"}),
		prometheus.NewCounterVec(prometheus.CounterOpts{Name: "otziv_queue_discarded"}, []string{"user"}))
}

func otzivStop(t *testing.T, q *RequestQueue) <-chan error {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- q.stopping(nil) }()
	t.Cleanup(func() {
		// Failed RED assertions must not leak the intentionally stuck upstream waiter.
		// This is test cleanup only; production shutdown never discards queued work.
		q.mtx.Lock()
		q.queues = newUserQueues(1000)
		q.cond.Broadcast()
		q.mtx.Unlock()
	})
	return done
}

func otzivAwaitStop(t *testing.T, done <-chan error) {
	t.Helper()
	select {
	case err := <-done:
		if err != nil { t.Fatal(err) }
	case <-time.After(time.Second):
		t.Error("shutdown remained blocked after all admitted requests were dequeued")
	}
}

func TestOtzivStopDrainedQueueWithoutCleanupTimer(t *testing.T) {
	q := otzivQueue()
	if err := q.EnqueueRequest("tenant", &otzivRequest{1}); err != nil { t.Fatal(err) }
	batch, _, err := q.GetNextRequestForQuerier(context.Background(), FirstUser(), make([]Request, 1))
	if err != nil || len(batch) != 1 { t.Fatalf("dequeue: %v / %d", err, len(batch)) }
	// No live timer can remove the now-empty user queue during service shutdown.
	otzivAwaitStop(t, otzivStop(t, q))
}

func TestOtzivStopWaitsForEveryAdmittedRequestAndRejectsAdmission(t *testing.T) {
	q := otzivQueue()
	for id := 0; id < 60; id++ {
		if err := q.EnqueueRequest([]string{"a", "b", "c"}[id%3], &otzivRequest{id}); err != nil { t.Fatal(err) }
	}
	waiting := make(chan struct{})
	var once sync.Once
	q.cond.testHookBeforeWaiting = func() { once.Do(func() { close(waiting) }) }
	done := otzivStop(t, q)
	select {
	case <-waiting:
	case <-time.After(time.Second): t.Fatal("shutdown did not wait for queued requests")
	}
	select {
	case <-done: t.Fatal("shutdown discarded pending work")
	default:
	}
	// Both an existing tenant and the RLock-to-Lock new-tenant path must reject writes.
	for _, tenant := range []string{"a", "new"} {
		if err := q.EnqueueRequest(tenant, &otzivRequest{999}); !errors.Is(err, ErrStopped) {
			t.Errorf("admission during drain: %s: %v", tenant, err)
		}
	}
	seen := map[int]bool{}
	for len(seen) < 60 {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		batch, _, err := q.GetNextRequestForQuerier(ctx, FirstUser(), make([]Request, 7))
		cancel()
		if err != nil { t.Fatal(err) }
		for _, value := range batch {
			id := value.(*otzivRequest).id
			if id >= 60 || seen[id] { t.Fatalf("unexpected or duplicate delivery %d", id) }
			seen[id] = true
		}
	}
	otzivAwaitStop(t, done)
}

func TestOtzivAdmissionRecheckedAfterReadLockUpgrade(t *testing.T) {
	q := otzivQueue()
	otzivAwaitStop(t, otzivStop(t, q))
	// A producer that passed its initial admission check can reach this helper
	// after shutdown wins the lock. The helper must recheck before creating a queue.
	q.mtx.RLock()
	_, release, err := q.getQueueUnderRlock("late-tenant")
	release()
	if !errors.Is(err, ErrStopped) { t.Fatalf("post-lock admission recheck missing: %v", err) }
	q.mtx.RLock()
	defer q.mtx.RUnlock()
	if q.queues.len() != 0 { t.Fatal("late producer created a queue after shutdown") }
}

func TestOtzivConcurrentAdmissionAndDrainDeliversAcceptedRequestsExactlyOnce(t *testing.T) {
	q := otzivQueue()
	accepted := make(chan int, 200)
	delivered := make(chan int, 200)
	if err := q.EnqueueRequest("a", &otzivRequest{-1}); err != nil { t.Fatal(err) }
	accepted <- -1
	start := make(chan struct{})
	producerDone := make(chan struct{})
	readerDone := make(chan struct{})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	go func() {
		defer close(producerDone)
		<-start
		for id := 0; id < 199; id++ {
			if q.EnqueueRequest([]string{"a", "b"}[id%2], &otzivRequest{id}) == nil { accepted <- id }
		}
	}()
	go func() {
		defer close(readerDone)
		for {
			batch, _, err := q.GetNextRequestForQuerier(ctx, FirstUser(), make([]Request, 3))
			if err != nil { return }
			for _, value := range batch { delivered <- value.(*otzivRequest).id }
		}
	}()
	close(start)
	done := otzivStop(t, q)
	<-producerDone
	otzivAwaitStop(t, done)
	cancel()
	<-readerDone
	close(accepted)
	close(delivered)
	want := map[int]bool{}
	for id := range accepted { want[id] = true }
	for id := range delivered {
		if !want[id] { t.Fatalf("unadmitted or duplicate request %d", id) }
		delete(want, id)
	}
	if len(want) != 0 { t.Fatalf("lost %d admitted requests", len(want)) }
}
