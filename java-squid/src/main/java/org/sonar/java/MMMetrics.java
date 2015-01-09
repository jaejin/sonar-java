package org.sonar.java;

import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;

import java.util.Arrays;
import java.util.List;

/**
 * User: jaejin
 * Date: 1/8/15
 * Time: 11:41 AM
 */
public  final class MMMetrics implements org.sonar.api.measures.Metrics {

    public static final Metric NCLOC_BY_CC_DISTRIB = new Metric("sigmm-ncloc-by-cc", "SIG NCLOC by CC",
            "Repartition of the ncloc on cc range", Metric.ValueType.DISTRIB, -1, false, CoreMetrics.DOMAIN_GENERAL);
    public static final Metric NCLOC_BY_NCLOC_DISTRIB = new Metric("sigmm-ncloc-by-ncloc", "SIG NCLOC by CC",
            "Repartition of the ncloc on ncloc range", Metric.ValueType.DISTRIB, -1, false, CoreMetrics.DOMAIN_GENERAL);

    @Override
    public List<Metric> getMetrics() {
        return Arrays.asList(NCLOC_BY_CC_DISTRIB, NCLOC_BY_NCLOC_DISTRIB);
    }
}
