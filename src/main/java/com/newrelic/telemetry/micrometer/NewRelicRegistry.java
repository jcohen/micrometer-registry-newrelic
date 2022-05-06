/*
 * Copyright 2020 New Relic Corporation. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.newrelic.telemetry.micrometer;

import com.newrelic.telemetry.Attributes;
import com.newrelic.telemetry.SenderConfiguration;
import com.newrelic.telemetry.TelemetryClient;
import com.newrelic.telemetry.metrics.Metric;
import com.newrelic.telemetry.metrics.MetricBatch;
import com.newrelic.telemetry.metrics.MetricBatchSender;
import com.newrelic.telemetry.micrometer.transform.AttributesMaker;
import com.newrelic.telemetry.micrometer.transform.BareMeterTransformer;
import com.newrelic.telemetry.micrometer.transform.CommonCounterTransformer;
import com.newrelic.telemetry.micrometer.transform.CounterAdapter;
import com.newrelic.telemetry.micrometer.transform.DistributionSummaryTransformer;
import com.newrelic.telemetry.micrometer.transform.FunctionCounterAdapter;
import com.newrelic.telemetry.micrometer.transform.FunctionTimerTransformer;
import com.newrelic.telemetry.micrometer.transform.GaugeTransformer;
import com.newrelic.telemetry.micrometer.transform.HistogramGaugeCustomizer;
import com.newrelic.telemetry.micrometer.transform.LongTaskTimerTransformer;
import com.newrelic.telemetry.micrometer.transform.TimeGaugeTransformer;
import com.newrelic.telemetry.micrometer.transform.TimerTransformer;
import com.newrelic.telemetry.micrometer.util.TimeTracker;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.step.StepDistributionSummary;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepTimer;
import io.micrometer.core.instrument.util.MeterPartition;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewRelicRegistry extends StepMeterRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(NewRelicRegistry.class);
  private static final String implementationVersion;

  private final NewRelicRegistryConfig config;
  private final TelemetryClient telemetryClient;
  private final Attributes commonAttributes;
  private final TimeGaugeTransformer timeGaugeTransformer;
  private final GaugeTransformer gaugeTransformer;
  private final TimerTransformer timerTransformer;

  private final FunctionTimerTransformer functionTimerTransformer;
  private final CommonCounterTransformer<Counter> counterTransformer;
  private final LongTaskTimerTransformer longTaskTimerTransformer;
  private final CommonCounterTransformer<FunctionCounter> functionCounterTransformer;
  private final DistributionSummaryTransformer distributionSummaryTransformer;
  private final BareMeterTransformer bareMeterTransformer;
  private final TimeTracker timeTracker;
  private final HistogramGaugeCustomizer histogramCustomizer;

  static {
    Package thisPackage = NewRelicRegistry.class.getPackage();
    implementationVersion =
        Optional.ofNullable(thisPackage.getImplementationVersion()).orElse("UnknownVersion");
  }

  protected NewRelicRegistry(
      NewRelicRegistryConfig config,
      Clock clock,
      Attributes commonAttributes,
      AttributesMaker attributesMaker,
      TimeTracker timeTracker,
      MetricBatchSender metricBatchSender) {
    this(
        config,
        clock,
        commonAttributes,
        new TelemetryClient(metricBatchSender, null, null, null),
        new TimeGaugeTransformer(new GaugeTransformer(clock, attributesMaker)),
        new GaugeTransformer(clock, attributesMaker),
        new TimerTransformer(timeTracker),
        new FunctionTimerTransformer(timeTracker),
        new CommonCounterTransformer<>(timeTracker, attributesMaker, CounterAdapter::new),
        new LongTaskTimerTransformer(clock),
        new CommonCounterTransformer<>(timeTracker, attributesMaker, FunctionCounterAdapter::new),
        new DistributionSummaryTransformer(timeTracker, attributesMaker),
        new BareMeterTransformer(clock),
        new HistogramGaugeCustomizer(),
        timeTracker);
  }

  NewRelicRegistry(
      NewRelicRegistryConfig config,
      Clock clock,
      Attributes commonAttributes,
      TelemetryClient telemetryClient,
      TimeGaugeTransformer timeGaugeTransformer,
      GaugeTransformer gaugeTransformer,
      TimerTransformer timerTransformer,
      FunctionTimerTransformer functionTimerTransformer,
      CommonCounterTransformer<Counter> counterTransformer,
      LongTaskTimerTransformer longTaskTimerTransformer,
      CommonCounterTransformer<FunctionCounter> functionCounterTransformer,
      DistributionSummaryTransformer distributionSummaryTransformer,
      BareMeterTransformer bareMeterTransformer,
      HistogramGaugeCustomizer histogramCustomizer,
      TimeTracker timeTracker) {
    super(config, clock);
    this.config = config;
    this.commonAttributes =
        commonAttributes
            .copy()
            .put("instrumentation.provider", "micrometer")
            .put("collector.name", "micrometer-registry-newrelic")
            .put("collector.version", implementationVersion);
    if (config.serviceName() != null) {
      this.commonAttributes.put("service.name", config.serviceName());
    }
    this.telemetryClient = telemetryClient;
    this.timeGaugeTransformer = timeGaugeTransformer;
    this.gaugeTransformer = gaugeTransformer;
    this.timerTransformer = timerTransformer;
    this.functionTimerTransformer = functionTimerTransformer;
    this.counterTransformer = counterTransformer;
    this.longTaskTimerTransformer = longTaskTimerTransformer;
    this.functionCounterTransformer = functionCounterTransformer;
    this.distributionSummaryTransformer = distributionSummaryTransformer;
    this.bareMeterTransformer = bareMeterTransformer;
    this.timeTracker = timeTracker;
    this.histogramCustomizer = histogramCustomizer;
  }

  @Override
  public void start(ThreadFactory threadFactory) {
    LOG.info("New Relic Registry: Version " + implementationVersion + " is starting");
    super.start(threadFactory);
  }

  @Override
  public void close() {
    super.close();
    // NOTE: telemetryClient.shutdown is called after calling "close"
    // so that we can flush the last metricBatch
    this.telemetryClient.shutdown();
  }

  @Override
  protected void publish() {
    doPublish(MeterPartition.partition(this, config.batchSize()));
  }

  protected void doPublish(List<List<Meter>> partitionedData) {
    for (List<Meter> batch : partitionedData) {
      List<Metric> metrics = new ArrayList<>();
      batch.forEach(
          meter -> {
            if (meter instanceof TimeGauge) {
              metrics.add(timeGaugeTransformer.transform((TimeGauge) meter));
            } else if (meter instanceof Gauge) {
              metrics.add(gaugeTransformer.transform((Gauge) meter));
            } else if (meter instanceof Timer) {
              metrics.addAll(timerTransformer.transform((Timer) meter));
            } else if (meter instanceof FunctionTimer) {
              metrics.addAll(functionTimerTransformer.transform((FunctionTimer) meter));
            } else if (meter instanceof Counter) {
              metrics.add(counterTransformer.transform((Counter) meter));
            } else if (meter instanceof DistributionSummary) {
              metrics.addAll(distributionSummaryTransformer.transform((DistributionSummary) meter));
            } else if (meter instanceof LongTaskTimer) {
              metrics.addAll(longTaskTimerTransformer.transform((LongTaskTimer) meter));
            } else if (meter instanceof FunctionCounter) {
              metrics.add(functionCounterTransformer.transform((FunctionCounter) meter));
            } else {
              metrics.addAll(bareMeterTransformer.transform(meter));
            }
          });
      telemetryClient.sendBatch(new MetricBatch(metrics, commonAttributes));
    }
    timeTracker.tick();
  }

  @Override
  protected TimeUnit getBaseTimeUnit() {
    return TimeUnit.MILLISECONDS;
  }

  public static NewRelicRegistryBuilder builder(NewRelicRegistryConfig config) {
    return new NewRelicRegistryBuilder(config);
  }

  @Override
  protected Timer newTimer(
      Meter.Id id,
      DistributionStatisticConfig distributionStatisticConfig,
      PauseDetector pauseDetector) {
    Timer timer =
        new StepTimer(
            id,
            clock,
            distributionStatisticConfig,
            pauseDetector,
            getBaseTimeUnit(),
            this.config.step().toMillis(),
            false);
    histogramCustomizer.registerHistogramGauges(timer, this);
    return timer;
  }

  @Override
  protected DistributionSummary newDistributionSummary(
      Meter.Id id, DistributionStatisticConfig distributionStatisticConfig, double scale) {
    DistributionSummary summary =
        new StepDistributionSummary(
            id, clock, distributionStatisticConfig, scale, config.step().toMillis(), false);
    histogramCustomizer.registerHistogramGauges(summary, this);
    return summary;
  }

  public static class NewRelicRegistryBuilder {

    private final NewRelicRegistryConfig config;
    private HttpSender httpSender = new HttpUrlConnectionSender();
    private Attributes commonAttributes = new Attributes();

    /**
     * In concert with {@link NewRelicRegistryBuilder#build(RegistryFactory)}, allows for
     * construction of registries that extend {@link NewRelicRegistry} without having to expose
     * additional internal details (like construction of a {@link MetricBatchSender}).
     *
     * <p>For example, to create a registry that extends {@code NewRelicRegistry}, but requires some
     * additional custom configuration, you could do:
     *
     * <pre>
     * public static class MyRegistryExtension implements NewRelicRegistryBuilder.RegistryFactory {
     *   private final int someCustomField;
     *
     *   public MyRegistryExtension(int someCustomField) {
     *     this.someCustomField = someCustomField;
     *   }
     *
     *   &#64;Override
     *   public NewRelicRegistry construct(
     *     NewRelicRegistryConfig config,
     *     Clock clock,
     *     Attributes commonAttributes,
     *     AttributesMaker attributesMaker,
     *     TimeTracker timeTracker,
     *     MetricBatchSender metricBatchSender) {
     *
     *     return new MyRegistryExtension(
     *       someCustomField,
     *       config,
     *       clock,
     *       commonAttributes,
     *       attributesMaker,
     *       timeTracker,
     *       metricBatchSender);
     *   }
     * }
     * </pre>
     */
    public interface RegistryFactory {

      /**
       * Construct an instance of a {@code NewRelicRegistry} (or a registry that extends {@code
       * NewRelicRegistry}).
       */
      NewRelicRegistry construct(
          NewRelicRegistryConfig config,
          Clock clock,
          Attributes commonAttributes,
          AttributesMaker attributesMaker,
          TimeTracker timeTracker,
          MetricBatchSender metricBatchSender);
    }

    public NewRelicRegistryBuilder(NewRelicRegistryConfig config) {
      this.config = config;
    }

    public NewRelicRegistryBuilder httpSender(HttpSender httpSender) {
      this.httpSender = httpSender;
      return this;
    }

    /**
     * Supply a set of attributes that should be applied to all metrics.
     *
     * @param commonAttributes The attributes that relate to all metrics
     * @return {@literal this}
     */
    public NewRelicRegistryBuilder commonAttributes(Attributes commonAttributes) {
      this.commonAttributes = commonAttributes;
      return this;
    }

    public NewRelicRegistry build() {
      return build(NewRelicRegistry::new);
    }

    public NewRelicRegistry build(RegistryFactory factory) {
      MetricBatchSender metricBatchSender = createMetricBatchSender();
      return factory.construct(
          config,
          Clock.SYSTEM,
          commonAttributes,
          new AttributesMaker(),
          new TimeTracker(Clock.SYSTEM),
          metricBatchSender);
    }

    private MetricBatchSender createMetricBatchSender() {
      SenderConfiguration.SenderConfigurationBuilder builder =
          MetricBatchSender.configurationBuilder()
              .apiKey(config.apiKey())
              .useLicenseKey(config.useLicenseKey())
              .httpPoster(new MicrometerHttpPoster(httpSender))
              .secondaryUserAgent("NewRelic-Micrometer-Exporter/" + implementationVersion)
              .auditLoggingEnabled(config.enableAuditMode());
      builder = configureEndpoint(builder);
      return MetricBatchSender.create(builder.build());
    }

    private SenderConfiguration.SenderConfigurationBuilder configureEndpoint(
        SenderConfiguration.SenderConfigurationBuilder builder) {
      if (config.uri() == null) {
        return builder;
      }
      try {
        URI uri = URI.create(config.uri());
        return builder.endpoint(uri.toURL());
      } catch (MalformedURLException e) {
        throw new RuntimeException("Invalid URI for the metric API : " + config.uri(), e);
      }
    }
  }
}
