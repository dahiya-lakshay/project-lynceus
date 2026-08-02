package com.lynceus.transaction.repository.projection;

/**
 * Interface-based projection for {@code FraudScoreRepository.scoreDistribution}'s native aggregate
 * query. {@code bucketIndex} is 0-8 for scores in [0.0, 0.9) (one per 0.1-wide range) and 9 for
 * scores in [0.9, 1.0] — the upper bound is inclusive so a perfect ensemble score of exactly 1.0000
 * still lands in the last bucket instead of an eleventh, out-of-range one (see the query's
 * Javadoc).
 */
public interface ScoreBucketProjection {

  int getBucketIndex();

  long getBucketCount();
}
