/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.FileIO.Write.FileNaming;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.parquet.ParquetIO;
import org.apache.beam.sdk.io.parquet.ParquetIO.Sink;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Contextful;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);
    private static PipelineResult result;

    public static void main(final String[] args) {
        try {
            final Schema inputSchema = new Schema.Parser().parse(
                    new String(App.class.getResourceAsStream("/inputSchema.json").readAllBytes(),
                            StandardCharsets.UTF_8));

            final Schema outputSchema = new Schema.Parser().parse(
                    new String(App.class.getResourceAsStream("/outputSchema.json").readAllBytes(),
                            StandardCharsets.UTF_8));

            FlinkPipelineOptions pipelineOptions =
                    PipelineOptionsFactory.as(FlinkPipelineOptions.class);

            pipelineOptions.setFlinkMaster("local");

            final Pipeline p = Pipeline.create(pipelineOptions);

            PCollection<PubsubMessage> data = p.apply(PubsubIO.readMessages().asBatch(10000, null)
                    .fromTopic("projects/pubsub-public-data/topics/taxirides-realtime"));

            Map<String, String> configuration=new HashMap<>();
            Sink sink = ParquetIO.sink(outputSchema).withConfiguration(configuration);

            data.apply(ParDo.of(new DoFn<PubsubMessage, GenericRecord>() {
                private static final long serialVersionUID = -1L;

                @ProcessElement
                public void processElement(@Element final PubsubMessage input,
                        final OutputReceiver<GenericRecord> output) {
                    final byte[] payload = input.getPayload();
                    final String message = new String(payload, StandardCharsets.UTF_8);
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

                    .apply(FileIO.<ShardInfo, GenericRecord>writeDynamic()
                            .by(Contextful.fn(r -> new ShardInfo(r)))
                            .withDestinationCoder(AvroCoder.of(ShardInfo.class))
                            .via(Contextful.fn(rec -> rec), sink)
                            .withNaming(Contextful.fn(shard -> new FileNaming() {
                                private static final long serialVersionUID = 1L;

                                @Override
                                public String getFilename(BoundedWindow window, PaneInfo pane,
                                        int numShards, int shardIndex, Compression compression) {
                                    return String.format("ride_status=%s/%s.parquet",
                                            shard.rideStatus, shard.date);
                                }
                            })).withNumShards(1).to("./output").withTempDirectory("./tmp"));

            result = null;
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    if (result != null) {
                        try {
                            result.cancel();
                            result.waitUntilFinish();
                        } catch (IOException e) {
                            LOG.error("", e);
                        }
                    }
                    LOG.info("Graceful exit");
                }
            });
            result = p.run();
        } catch (final Exception e) {
            LOG.error("", e);
        }
    }
}
