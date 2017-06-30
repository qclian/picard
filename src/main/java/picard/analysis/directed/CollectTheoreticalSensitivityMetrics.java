package picard.analysis.directed;

import htsjdk.samtools.metrics.Header;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.Histogram;
import picard.PicardException;
import picard.analysis.TheoreticalSensitivity;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.io.FileReader;

/**
 * Created by fleharty on 4/28/17.
 */
public class CollectTheoreticalSensitivityMetrics extends CommandLineProgram {
    @Option(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "An aligned SAM or BAM file.")
    public File INPUT;
    protected int doWork() {

        final MetricsFile Metrics = new MetricsFile();
        try {
            Metrics.read(new FileReader(INPUT));
        }
        catch(Exception e) {

        }
        final List<Histogram> histograms = Metrics.getAllHistograms();
        final List<Header> headers = Metrics.getHeaders();

        headers.get(1).toString();

        final Histogram depthHistogram = histograms.get(0);
        final Histogram qualityHistogram = histograms.get(1);

        final double[] qualityDistribution = TheoreticalSensitivity.normalizeHistogram(qualityHistogram);
        final double[] depthDistribution = TheoreticalSensitivity.normalizeHistogram(depthHistogram);

        final double results = TheoreticalSensitivity.theoreticalSensitivity(depthDistribution, qualityDistribution, 3, 10000, 0.1);
//        final double result = TheoreticalSensitivity.sensitivityAtConstantDepth(depth, qualityDistribution, 3, sampleSize, alleleFraction);

        return 0;
    }
}
