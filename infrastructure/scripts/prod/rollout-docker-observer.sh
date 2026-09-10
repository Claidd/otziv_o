#!/bin/sh
# Sourced by the existing deploy shell. These callbacks preserve its Compose
# env/profile handling and health/recreate behavior, rather than launching a
# second project or a raw socket consumer.
verify_observer_logflow() (
  consumer="$1"
  observer_id="$(compose ps -q docker-observer)"
  observer_image="$(docker inspect --format '{{.Image}}' "$observer_id")"
  observer_project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$observer_id")"
  network_id="$(docker network ls -q --filter "label=com.docker.compose.project=$observer_project" --filter 'label=com.docker.compose.network=internal_net')"
  case "$network_id" in ''|*[!a-f0-9]*) echo 'Observer probe requires exactly one private application network.' >&2; exit 1;; esac
  marker="OTZIV_DEPLOY_$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
  docker run --rm --name "otziv-observer-probe-${marker#OTZIV_DEPLOY_}" \
    --label "dozzle.name=$marker" --label "com.otziv.observer-deploy.marker=$marker" \
    --network "$network_id" --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --memory 128m --pids-limit 64 --pull never \
    "$observer_image" node deployment-probe.cjs "$consumer" "$marker"
)

rollout_docker_observer() {
  # The deployment bundle supplies the published digest already included in the
  # disk preflight. Never allocate an unbudgeted build cache on the VPS.
  compose pull docker-observer || return
  recreate_service_with_retry docker-observer || return
  wait_service_healthy docker-observer 120 || return
  recreate_service_with_retry dozzle || return
  wait_service_healthy dozzle 120 || return
  verify_observer_logflow dozzle || return
  recreate_service_with_retry alloy || return
  wait_service_healthy alloy 120 || return
  verify_observer_logflow alloy || return
}
