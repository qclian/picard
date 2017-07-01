/*
 * The MIT License
 *
 * Copyright (c) 2015 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.analysis;

import htsjdk.samtools.metrics.MetricBase;
import htsjdk.samtools.util.Histogram;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import picard.PicardException;
import picard.util.MathUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Created by David Benjamin on 5/13/15.
 */
public class TheoreticalSensitivity {

    private static final Log log = Log.getInstance(TheoreticalSensitivity.class);
    private static final int SAMPLING_MAX = 600; //prevent 'infinite' loops
    private static final int MAX_CONSIDERED_DEPTH = 10000; //no point in looking any deeper than this, otherwise GC overhead is too high.
    private static int randomSeed = 51;

    /**
     * @param depthDistribution   the probability of depth n is depthDistribution[n] for n = 0, 1. . . N - 1
     * @param qualityDistribution the probability of quality q is qualityDistribution[q] for q = 0, 1. . . Q
     * @param sampleSize          sample size is the number of random sums of quality scores for each m
     * @param logOddsThreshold    is the log_10 of the likelihood ratio required to call a SNP,
     *                            for example 5 if the variant likelihood must be 10^5 times greater
     */
    public static double hetSNPSensitivity(final double[] depthDistribution, final double[] qualityDistribution,
                                           final int sampleSize, final double logOddsThreshold) {
        return hetSNPSensitivity(depthDistribution, qualityDistribution, sampleSize, logOddsThreshold, true);
    }

    /**
     * @param depthDistribution   the probability of depth n is depthDistribution[n] for n = 0, 1. . . N - 1
     * @param qualityDistribution the probability of quality q is qualityDistribution[q] for q = 0, 1. . . Q
     * @param sampleSize          sample size is the number of random sums of quality scores for each m
     * @param logOddsThreshold    is the log_10 of the likelihood ratio required to call a SNP,
     *                            for example 5 if the variant likelihood must be 10^5 times greater.
     * @param withLogging         true to output log messages, false otherwise.
     */
    public static double hetSNPSensitivity(final double[] depthDistribution, final double[] qualityDistribution,
                                           final int sampleSize, final double logOddsThreshold, final boolean withLogging) {
        final int N = Math.min(depthDistribution.length, MAX_CONSIDERED_DEPTH + 1);

        if (withLogging) log.info("Creating Roulette Wheel");
        final RouletteWheel qualitySampler = new RouletteWheel(qualityDistribution);

        //qualitySums[m] is a random sample of sums of m quality scores, for m = 0, 1, N - 1
        if (withLogging) log.info("Calculating quality sums from quality sampler");
        final List<ArrayList<Integer>> qualitySums = qualitySampler.sampleCumulativeSums(N, sampleSize, withLogging);

        //if a quality sum of m qualities exceeds the quality sum threshold for n total reads, a SNP is called
        final ArrayList<Double> qualitySumThresholds = new ArrayList<>(N);
        final double LOG_10 = Math.log10(2);

        for (int n = 0; n < N; n++) qualitySumThresholds.add(10 * (n * LOG_10 + logOddsThreshold));

        //probabilityToExceedThreshold[m][n] is the probability that the sum of m quality score
        //exceeds the nth quality sum threshold
        if (withLogging) log.info("Calculating theoretical het sensitivity");
        final List<ArrayList<Double>> probabilityToExceedThreshold = proportionsAboveThresholds(qualitySums, qualitySumThresholds);
        final List<ArrayList<Double>> altDepthDistribution = hetAltDepthDistribution(N);
        double result = 0.0;
        for (int n = 0; n < N; n++) {
            for (int m = 0; m <= n; m++) {
                result += depthDistribution[n] * altDepthDistribution.get(n).get(m) * probabilityToExceedThreshold.get(m).get(n);
            }
        }
        return result;
    }

    //given L lists of lists and N thresholds, count the proportion of each list above each threshold
    public static List<ArrayList<Double>> proportionsAboveThresholds(final List<ArrayList<Integer>> lists, final List<Double> thresholds) {
        final ArrayList<ArrayList<Double>> result = new ArrayList<>();

        for (final ArrayList<Integer> list : lists) {
            final ArrayList<Double> newRow = new ArrayList<>(Collections.nCopies(thresholds.size(), 0.0));
            Collections.sort(list);
            int n = 0;
            int j = 0;  //index within the ordered sample
            while (n < thresholds.size() && j < list.size()) {
                if (thresholds.get(n) > list.get(j)) j++;
                else newRow.set(n++, (double) (list.size() - j) / list.size());
            }
            result.add(newRow);
        }
        return result;
    }

    //Utility function for making table of binomial distribution probabilities nCm * (0.5)^n
    //for n = 0, 1 . . . N - 1 and m = 0, 1. . . n
    public static List<ArrayList<Double>> hetAltDepthDistribution(final int N) {
        final List<ArrayList<Double>> table = new ArrayList<>();
        for (int n = 0; n < N; n++) {
            final ArrayList<Double> nthRow = new ArrayList<>();

            //add the 0th element, then elements 1 through n - 1, then the nth.
            //Note that nCm = (n-1)C(m-1) * (n/m)
            nthRow.add(Math.pow(0.5, n));
            for (int m = 1; m < n; m++) nthRow.add((n * 0.5 / m) * table.get(n - 1).get(m - 1));
            if (n > 0) nthRow.add(nthRow.get(0));

            table.add(nthRow);
        }
        return table;
    }

    /*
    Perform random draws from {0, 1. . . N - 1} according to a list of relative probabilities.

    We use an O(1) stochastic acceptance algorithm -- see Physica A, Volume 391, Page 2193 (2012) --
    which works well when the ratio of maximum weight to average weight is not large.
     */
    public static class RouletteWheel {
        final private List<Double> probabilities;
        final private int N;
        private int count = 0;
        private Random rng;

        RouletteWheel(final double[] weights) {
            rng = new Random(randomSeed);
            N = weights.length;

            probabilities = new ArrayList<>();
            final double wMax = MathUtil.max(weights);

            if (wMax == 0) {
                throw new PicardException("Quality score distribution is empty.");
            }

            for (final double w : weights) {
                probabilities.add(w / wMax);
            }
        }

        public int draw() {
            while (true) {
                final int n = (int) (N * rng.nextDouble());
                count++;
                if (rng.nextDouble() < probabilities.get(n)) {
                    count = 0;
                    return n;
                } else if (count >= SAMPLING_MAX) {
                    count = 0;
                    return 0;
                }
            }
        }

        //get samples of sums of 0, 1, 2,. . .  N - 1 draws
        public List<ArrayList<Integer>> sampleCumulativeSums(final int maxNumberOfSummands, final int sampleSize, final boolean withLogging) {
            final List<ArrayList<Integer>> result = new ArrayList<>();
            for (int m = 0; m < maxNumberOfSummands; m++) result.add(new ArrayList<>());

            for (int iteration = 0; iteration < sampleSize; iteration++) {
                int cumulativeSum = 0;
                for (int m = 0; m < maxNumberOfSummands; m++) {
                    result.get(m).add(cumulativeSum);
                    cumulativeSum += draw();
                }
                if (withLogging && iteration % 1000 == 0) {
                    log.info(iteration + " sampling iterations completed");
                }
            }
            return result;
        }
    }

    public static double[] normalizeHistogram(final Histogram<Integer> histogram) {
        if (histogram == null) throw new PicardException("Histogram is null and cannot be normalized");

        final double histogramSumOfValues = histogram.getSumOfValues();
        final double[] normalizedHistogram = new double[histogram.size()];

        for (int i = 0; i < histogram.size(); i++) {
            if (histogram.get(i) != null) {
                normalizedHistogram[i] = histogram.get(i).getValue() / histogramSumOfValues;
            }
        }
        return normalizedHistogram;
    }

    /**
     * Determines if a het variant would be called
     * @param totalDepth Depth at the site to be called, both alt and ref.
     * @param altDepth Number of alt bases at this site.
     * @param averageQuality Average Phred-scaled quality of bases
     * @param alleleFraction Allele fraction we are attempting to detect
     * @param logOddsThreshold Log odds threshold necessary to exceed for variant to be called
     * @return
     */
    public static boolean isCalled(int totalDepth, int altDepth, double averageQuality, double alleleFraction, double logOddsThreshold) {
        double threshold;
        double sumOfQualities = altDepth * averageQuality;
        threshold = 10.0 * (altDepth * Math.log10(1.0 / alleleFraction) + (totalDepth - altDepth) * Math.log10(1.0 / (1.0 - alleleFraction)) + logOddsThreshold);

        return sumOfQualities > threshold;
    }

    /**
     * Draw from a binomial distribution
     * @param trials Number of trials to perform
     * @param p Probability of individual success
     * @return Number of total successes
     */

    public static int binomialDraw(final int trials, final double p, final Random uniformRNG) {
        if (p > 1.0 || p < 0) {
            throw new PicardException("Probabilities should be between 0 and 1, found value " + p + ".");
        }

        int successes = 0;
        for (int i = 0; i < trials; i++) {
            if (uniformRNG.nextDouble() < p) {
                successes++;
            }
        }
        return successes;
    }

    public static double sensitivityAtConstantDepth(final int depth, final double[] qualityDistribution, final double logOddsThreshold, final int sampleSize, final double alleleFraction, final int randomSeed) {
        RouletteWheel qualityRW = new RouletteWheel(trimDistribution(qualityDistribution));
        Random uniformRNG = new Random(randomSeed);

        int altDepth = 0;
        int calledVariants = 0;
        for (int k = 0; k < sampleSize; k++) {
            altDepth = binomialDraw(depth, alleleFraction, uniformRNG);

            int sumOfQualities = 0;
            for (int i = 0; i < altDepth; i++) {
                sumOfQualities += qualityRW.draw();
            }
            if (isCalled(depth, altDepth, (double) sumOfQualities / (double) altDepth, alleleFraction, logOddsThreshold)) {
                calledVariants++;
            }
        }
        return (double) calledVariants / sampleSize;
    }

    public static double sensitivityAtConstantDepth(final int depth, final double[] qualityDistribution, final double logOddsThreshold, final int sampleSize, final double alleleFraction) {
        return sensitivityAtConstantDepth(depth, qualityDistribution, logOddsThreshold, sampleSize, alleleFraction, randomSeed);
    }

    public static double theoreticalSensitivity(final double[] depthDistribution, final double[] qualityDistribution,
                                                final int sampleSize, final double logOddsThreshold, final double alleleFraction) {

        double sensitivity = 0.0;
        for (int k = 0; k < depthDistribution.length; k++) {
            if(depthDistribution[k] > 0.1 / depthDistribution.length) {
                sensitivity += sensitivityAtConstantDepth(k, qualityDistribution, logOddsThreshold, sampleSize, alleleFraction) * depthDistribution[k];
            }
        }
        return sensitivity;
    }


    /**
     * Removes trailing zeros in a distribution.  The purpose of this function is to prevent other
     * functions from
     * @param distribution Distribution of base qualities
     * @return Distribution of base qualities removing any trailing zeros
     */
    public static double[] trimDistribution(final double[] distribution) {
        int k;
        for(k = distribution.length-1;k >= 0;k--) {
            if(distribution[k] != 0) {
                break;
            }
        }

        double[] trimmedDistribution = new double[k+1];
        for(int i = 0;i <= k;i++) {
            trimmedDistribution[i] = distribution[i];
        }

        return trimmedDistribution;
    }
}