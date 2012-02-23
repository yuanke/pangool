/**
 * Copyright [2012] [Datasalt Systems S.L.]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datasalt.pangool.examples;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import com.datasalt.pangool.cogroup.CoGrouper;
import com.datasalt.pangool.cogroup.CoGrouperException;
import com.datasalt.pangool.cogroup.processors.GroupHandler;
import com.datasalt.pangool.cogroup.processors.InputProcessor;
import com.datasalt.pangool.cogroup.sorting.SortBy;
import com.datasalt.pangool.cogroup.sorting.Criteria.Order;
import com.datasalt.pangool.io.tuple.ITuple;
import com.datasalt.pangool.io.tuple.Schema;
import com.datasalt.pangool.io.tuple.Tuple;
import com.datasalt.pangool.io.tuple.Schema.Field;

/**
 * Like original Hadoop's SecondarySort example. Reads a tabulated text file with two numbers, groups by the first and
 * sorts by both.
 */
public class SecondarySort {

	@SuppressWarnings("serial")
  private static class IProcessor extends InputProcessor<LongWritable, Text> {

		private Tuple tuple ;
		
		public void setup(CoGrouperContext context, Collector collector) throws IOException, InterruptedException {
			tuple = new Tuple(context.getCoGrouperConfig().getSourceSchema("my_schema"));
		}
		
		@Override
		public void process(LongWritable key, Text value, CoGrouperContext context, Collector collector)
		    throws IOException, InterruptedException {
			String[] fields = value.toString().trim().split(" ");
			tuple.set("first", Integer.parseInt(fields[0]));
			tuple.set("second", Integer.parseInt(fields[1]));
			collector.write(tuple);
		}
	}

	@SuppressWarnings("serial")
  public static class Handler extends GroupHandler<Text, NullWritable> {

		@Override
		public void onGroupElements(ITuple group, Iterable<ITuple> tuples, CoGrouperContext context, Collector collector)
		    throws IOException, InterruptedException, CoGrouperException {

			for(ITuple tuple : tuples) {
				collector.write(new Text(tuple.get("first") + "\t" + tuple.get("second")), NullWritable.get());
			}
		}
	}

	public Job getJob(Configuration conf, String input, String output) throws CoGrouperException, IOException {
		// Configure schema, sort and group by
		List<Field> fields = new ArrayList<Field>();
		fields.add(new Field("first",Integer.class));
		fields.add(new Field("second",Integer.class));
		
		Schema schema = new Schema("my_schema",fields);
		CoGrouper grouper = new CoGrouper(conf);
		grouper.addSourceSchema(schema);
		grouper.setGroupByFields("first");
		grouper.setOrderBy(new SortBy().add("first",Order.ASC).add("second",Order.ASC));
		// Input / output and such
		grouper.setGroupHandler(new Handler());
		grouper.setOutput(new Path(output), TextOutputFormat.class, Text.class, NullWritable.class);
		grouper.addInput(new Path(input), TextInputFormat.class, new IProcessor());
		return grouper.createJob();
	}
}