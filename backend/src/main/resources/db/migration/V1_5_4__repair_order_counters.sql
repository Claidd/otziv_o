UPDATE orders o
LEFT JOIN (
    SELECT od.order_detail_order AS order_id,
           COUNT(r.review_id) AS published_count
    FROM order_details od
    JOIN reviews r ON r.review_order_details = od.order_detail_id
    WHERE r.review_publish = 1
    GROUP BY od.order_detail_order
) published_reviews ON published_reviews.order_id = o.order_id
SET o.order_counter = COALESCE(published_reviews.published_count, 0)
WHERE o.order_counter <> COALESCE(published_reviews.published_count, 0);
