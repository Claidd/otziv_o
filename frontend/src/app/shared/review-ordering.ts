export function sortReviewsById<T extends { id: number | null | undefined }>(reviews: readonly T[] | null | undefined): T[] {
  return [...(reviews ?? [])].sort((left, right) => reviewIdValue(left) - reviewIdValue(right));
}

function reviewIdValue(review: { id: number | null | undefined }): number {
  return review.id ?? Number.MAX_SAFE_INTEGER;
}
