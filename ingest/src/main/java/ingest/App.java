/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.sun.jersey.core.impl.provider.entity.XMLRootObjectProvider.Text;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.beam.model.pipeline.v1.RunnerApi.Trigger.AfterEndOfWindow;
import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.parquet.ParquetIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sample;
import org.apache.beam.sdk.transforms.windowing.AfterAll;
import org.apache.beam.sdk.transforms.windowing.AfterEach;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(final String[] args) {
        try {
            final Schema inputSchema = new Schema.Parser().parse(
                    new String(App.class.getResourceAsStream("/inputSchema.json").readAllBytes(),
                            StandardCharsets.UTF_8));

            final Schema outputSchema = new Schema.Parser().parse(
                    new String(App.class.getResourceAsStream("/outputSchema.json").readAllBytes(),
                            StandardCharsets.UTF_8));

            final DirectOptions pipelineOptions = PipelineOptionsFactory.as(DirectOptions.class);
            final Pipeline p = Pipeline.create(pipelineOptions);

            PCollection<PubsubMessage> data = p.apply(PubsubIO.readMessages()
                    .fromTopic("projects/pubsub-public-data/topics/taxirides-realtime"));
            data = data
                    .apply(Window.<PubsubMessage>into(FixedWindows.of(Duration.standardSeconds(60)))
                            .withAllowedLateness(Duration.ZERO).discardingFiredPanes()
                            .triggering(Repeatedly.forever(AfterPane.elementCountAtLeast(1000))));

            data.apply(ParDo.of(new DoFn<PubsubMessage, GenericRecord>() {
                private static final long serialVersionUID = -1L;
                int cnt = 0;

                @ProcessElement
                public void processElement(@Element final PubsubMessage input,
                        final OutputReceiver<GenericRecord> output) {
                    final byte[] payload = input.getPayload();
                    final String message = new String(payload, StandardCharsets.UTF_8);
                    cnt++;
                    if (cnt > 1000) {
                        System.exit(0);
                    }
                    Decoder decoder;
                    try {
                        decoder = DecoderFactory.get().jsonDecoder(inputSchema, message);
                        final DatumReader<GenericData.Record> reader =
                                new GenericDatumReader<>(inputSchema);
                        output.output(reader.read(null, decoder));
                    } catch (final IOException e) {
                        LOG.error("", e);
                    }
                }
            })).setCoder(AvroCoder.of(inputSchema))
                    .apply(ParDo.of(new DoFn<GenericRecord, GenericRecord>() {
                        private static final long serialVersionUID = -1L;

                        @ProcessElement
                        public void processElement(@Element final GenericRecord input,
                                final OutputReceiver<GenericRecord> output) {
                            Record outputRecord = new GenericData.Record(outputSchema);
                            for (Field f : inputSchema.getFields()) {
                                Object value = input.get(f.name());
                                if (f.name().equals("timestamp")) {
                                    outputRecord.put("row_timestamp", value);
                                } else {
                                    outputRecord.put(f.name(), value);
                                }
                            }
                            output.output(outputRecord);
                        }
                    })).setCoder(AvroCoder.of(outputSchema))
                    .apply(FileIO.<GenericRecord>write().via(ParquetIO.sink(outputSchema))
                            .to("output/data.parquet").withNumShards(1));

            p.run();
        } catch (final Exception e) {
            LOG.error("", e);
        }
    }
}