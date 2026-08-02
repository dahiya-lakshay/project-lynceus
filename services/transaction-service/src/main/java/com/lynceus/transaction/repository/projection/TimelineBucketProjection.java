package com.lynceus.transaction.repository.projection;

import java.time.Instant;

/**
 * Interface-based projection for {@code FraudScoreRepository.scoreTimeline}'s native aggregate
 * query.
 */
public interface TimelineBucketProjection {

  Instant getBucketTimestamp();

  double getAvgScore();
}
