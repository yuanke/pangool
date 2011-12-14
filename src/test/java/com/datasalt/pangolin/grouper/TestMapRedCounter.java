/**
 * Copyright [2011] [Datasalt Systems S.L.]
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

package com.datasalt.pangolin.grouper;

import java.io.IOException;
import java.util.Iterator;

import junit.framework.Assert;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.junit.Test;

import com.datasalt.pangolin.commons.test.AbstractHadoopTestLibrary;
import com.datasalt.pangolin.grouper.io.DoubleBufferedTuple;
import com.datasalt.pangolin.grouper.io.Tuple;
import com.datasalt.pangolin.grouper.io.TupleImpl.InvalidFieldException;
import com.datasalt.pangolin.grouper.mapred.GrouperMapperHandler;
import com.datasalt.pangolin.grouper.mapred.GrouperReducerHandler;


public class TestMapRedCounter extends AbstractHadoopTestLibrary{

	private static class Mapy extends GrouperMapperHandler<Text,NullWritable>{
		
		//private Tuple outputKey;
		private FieldsDescription schema;
		
		@Override
		public void setup(Mapper.Context context) throws IOException,InterruptedException {
			super.setup(context);
			
			try {
	      this.schema = FieldsDescription.parse(context.getConfiguration());
	      //outputKey = new DoubleBufferedTuple(schema);
      } catch(GrouperException e) {
	      throw new RuntimeException(e);
      }
		}
		
		
		@Override
		public void map(Text key,NullWritable value) throws IOException,InterruptedException{
			try {
				Tuple outputKey = createTuple(key.toString(), schema);
				emit(outputKey);
			} catch (InvalidFieldException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private static class IdentityRed extends GrouperReducerHandler<Text,Text>{

		private Reducer<? extends Tuple,NullWritable,Text,Text>.Context context;
//		private Text outputKey = new Text();
//		private Text outputValue = new Text();
		private int [] count,distinctCount;
		private int minDepth=0;
		private int maxDepth=2;
		
		@Override
		public void setup(Reducer<? extends Tuple,NullWritable,Text,Text>.Context context) throws IOException,InterruptedException {
			this.context = context;
			count = new int[3];
			distinctCount = new int[3];
			
		}
		
		@Override
		public void cleanup(Reducer<? extends Tuple,NullWritable,Text,Text>.Context context) throws IOException,InterruptedException {
			
		}
		
		@Override
    public void onOpenGroup(int depth,String field,Tuple firstElement) throws IOException, InterruptedException {
			count[depth] = 0;
			distinctCount[depth]=0;
			
    }

		@Override
    public void onCloseGroup(int depth,String field,Tuple lastElement) throws IOException, InterruptedException {
			try {
				String tupleStr = lastElement.toString(0, depth);
				String output =  tupleStr +  " => count:" + count[depth];
				if (depth < maxDepth){
					//distinctCount is not set in highest depth
					output += " distinctCount:"+ distinctCount[depth];
				}
				System.out.println(output);
				if(depth > minDepth) {
					//we can't output data below minDepth.
					count[depth - 1] += count[depth];
					distinctCount[depth - 1]++;
				}
			} catch(InvalidFieldException e) {
				throw new RuntimeException(e);
			}
    }
		
		@Override
		public void onGroupElements(Iterable<Tuple> tuples) throws IOException,InterruptedException {
			Iterator<Tuple> iterator = tuples.iterator();

			try {
				while(iterator.hasNext()) {
					Tuple tuple = iterator.next();
					count[maxDepth] += tuple.getInt("count");
				}
			} catch(InvalidFieldException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	
	private static DoubleBufferedTuple createTuple(String text,FieldsDescription schema) throws InvalidFieldException{
		DoubleBufferedTuple tuple = new DoubleBufferedTuple(schema);
		String[] tokens = text.split(",");
		String user = tokens[0];
		Integer day = Integer.parseInt(tokens[1]);
		String url = tokens[2];
		
		tuple.setString("user",user);
		tuple.setInt("day",day);
		tuple.setString("url",url);
		tuple.setInt("count", 1);
		return tuple;
	}
	
	@Test
	public void test() throws IOException, InterruptedException, ClassNotFoundException, GrouperException, InstantiationException, IllegalAccessException{
		
		String[] inputElements = new String[]{
				"user1,1,url1",
				"user1,1,url1",
				"user1,1,url1",
				"user1,1,url2",
				"user1,1,url2",
				"user1,1,url2",
				"user1,1,url3",
				"user1,2,url1",
				"user1,2,url1",
				"user1,2,url1",
				"user1,2,url2",
				"user1,3,url4",
				"user1,3,url4",
				"user1,3,url5",
				"user2,1,url6",
				"user2,1,url6",
				"user2,1,url7",
				"user2,2,url8"
		};
		
		FieldsDescription schema = FieldsDescription.parse("user:string,day:vint,url:string,count:vint");
		int i=0; 
		for (String inputElement : inputElements){
			withInput("input",writable(inputElement));

		}
		
		GrouperWithRollup grouper = new GrouperWithRollup(getConf());
		grouper.setInputFormat(SequenceFileInputFormat.class);
		grouper.setOutputFormat(SequenceFileOutputFormat.class);
		grouper.setMapperHandler(Mapy.class);
		grouper.setReducerHandler(IdentityRed.class);
		
		grouper.setSchema(schema);
		grouper.setSortCriteria("user ASC,day ASC,url ASC");
		grouper.setMinGroup("user");
		grouper.setMaxGroup("user,day,url");
		
		grouper.setOutputKeyClass(Text.class);
		grouper.setOutputValueClass(Text.class);
		
		
		Job job = grouper.getJob();
		job.setNumReduceTasks(1);
		Path outputPath = new Path("output");
		Path inputPath = new Path("input");
		FileInputFormat.setInputPaths(job,inputPath);
		FileOutputFormat.setOutputPath(job, outputPath);
		
		assertRun(job);
		
	}
	
	
	
	
	
	
	private void assertOutput(SequenceFile.Reader reader,String expectedKey,Tuple expectedValue) throws IOException{
		Text actualKey=new Text();
		Text actualValue = new Text();
		reader.next(actualKey, actualValue);
		
		Assert.assertEquals(new Text(expectedKey),actualKey);
		Assert.assertEquals(new Text(expectedValue.toString()),actualValue);
	}
	
	
}
