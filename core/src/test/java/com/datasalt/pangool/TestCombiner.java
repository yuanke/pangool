package com.datasalt.pangool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.junit.Assert;
import org.junit.Test;

import com.datasalt.pangool.Criteria.Order;
import com.datasalt.pangool.Schema.Field;
import com.datasalt.pangool.api.CombinerHandler;
import com.datasalt.pangool.api.GroupHandler;
import com.datasalt.pangool.api.InputProcessor;
import com.datasalt.pangool.io.tuple.ITuple;
import com.datasalt.pangool.io.tuple.ITuple.InvalidFieldException;
import com.datasalt.pangool.io.tuple.Tuple;
import com.datasalt.pangool.test.AbstractHadoopTestLibrary;

public class TestCombiner extends AbstractHadoopTestLibrary{


	@SuppressWarnings("serial")
	public static class Split extends InputProcessor<Text, NullWritable> {

		private Tuple tuple;
		
		public void setup(CoGrouperContext context, Collector collector) throws IOException, InterruptedException {
			Schema schema = context.getCoGrouperConfig().getSourceSchema(0);
			this.tuple = new Tuple(schema);
			tuple.set("count", 1);
		}
		
		@Override
		public void process(Text key, NullWritable value, CoGrouperContext context, Collector collector)
		    throws IOException, InterruptedException {
			StringTokenizer itr = new StringTokenizer(key.toString());
			while(itr.hasMoreTokens()) {
				tuple.set("word", itr.nextToken());
				collector.write(tuple);
			}
		}
	}

	@SuppressWarnings("serial")
	public static class CountCombiner extends CombinerHandler {

		private Tuple tuple;
		
		public void setup(CoGrouperContext context, Collector collector) throws IOException, InterruptedException {
			Schema schema = context.getCoGrouperConfig().getSourceSchema("schema");
			this.tuple = new Tuple(schema);
		}

		@Override
		public void onGroupElements(ITuple group, Iterable<ITuple> tuples, CoGrouperContext context, Collector collector)
		    throws IOException, InterruptedException, CoGrouperException {
			int count = 0;
			tuple.set("word", group.get("word"));
			for(ITuple tuple : tuples) {
				count += (Integer) tuple.get(1);
			}
			tuple.set("count", count);
			collector.write(this.tuple);
		}
	}

	@SuppressWarnings("serial")
	public static class Count extends GroupHandler<Text, IntWritable> {

		private IntWritable countToEmit;
		
		public void setup(CoGrouperContext coGrouperContext, Collector collector) throws IOException, InterruptedException,
		    CoGrouperException {
			countToEmit = new IntWritable();
		};

		@Override
		public void onGroupElements(ITuple group, Iterable<ITuple> tuples, CoGrouperContext context, Collector collector)
		    throws IOException, InterruptedException, CoGrouperException {
			Iterator<ITuple> iterator = tuples.iterator();
			while(iterator.hasNext()){
				ITuple tuple = iterator.next();
				Text text = (Text)tuple.get("word");
				countToEmit.set((Integer)tuple.get("count"));
				collector.write(text, countToEmit);
				Assert.assertFalse(iterator.hasNext());
			}
		}
	}

	public Job getJob(Configuration conf, String input, String output) throws InvalidFieldException, CoGrouperException,
	    IOException {
		FileSystem fs = FileSystem.get(conf);
		fs.delete(new Path(output), true);

		List<Field> fields = new ArrayList<Field>();
		fields.add(new Field("word",String.class));
		fields.add(new Field("count",Integer.class));
		
		CoGrouper cg = new CoGrouper(conf);
		cg.addSourceSchema(new Schema("schema",fields));
		cg.setJarByClass(TestCombiner.class);
		cg.addInput(new Path(input), SequenceFileInputFormat.class, new Split());
		cg.setOutput(new Path(output), SequenceFileOutputFormat.class, Text.class, IntWritable.class);
		cg.setGroupByFields("word");
		cg.setOrderBy(new SortBy().add("word",Order.ASC));
		cg.setGroupHandler(new Count());
		cg.setCombinerHandler(new CountCombiner());

		return cg.createJob();
	}
	
	@Test
	public void test() throws CoGrouperException, IOException, InterruptedException,
	    ClassNotFoundException {
		
		
		Configuration conf = new Configuration();
		String input = "combiner-input";
		String output ="combiner-output";
		
		withInput(input,writable("hola don pepito hola don jose"));

		Job job = new TestCombiner().getJob(conf,input,output);
		job.setNumReduceTasks(1);
		assertRun(job);
		
		withOutput(output + "/part-r-00000",writable("don"),writable(2));
		withOutput(output+ "/part-r-00000",writable("hola"),writable(2));
		withOutput(output+ "/part-r-00000",writable("jose"),writable(1));
		withOutput(output+ "/part-r-00000",writable("pepito"),writable(1));
		
		trash(input);
		trash(output);
	}
}
