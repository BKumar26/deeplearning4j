/*-
 *  * Copyright 2016 Skymind, Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 */

package org.datavec.spark.functions;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.input.PortableDataStream;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.api.util.ClassPathResource;
import org.datavec.api.writable.ArrayWritable;
import org.datavec.api.writable.Writable;
import org.datavec.image.recordreader.ImageRecordReader;
import org.datavec.spark.BaseSparkTest;
import org.junit.Test;

import java.io.File;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TestRecordReaderFunction extends BaseSparkTest {

    @Test
    public void testRecordReaderFunction() throws Exception {

        ClassPathResource cpr = new ClassPathResource("/imagetest/0/a.bmp");
        List<String> labelsList = Arrays.asList("0", "1");   //Need this for Spark: can't infer without init call

        String path = cpr.getFile().getAbsolutePath();
        String folder = path.substring(0, path.length() - 7);
        path = folder + "*";

        JavaPairRDD<String,PortableDataStream> origData = sc.binaryFiles(path);
        assertEquals(4,origData.count());    //4 images

        ImageRecordReader irr = new ImageRecordReader(28,28,1,new ParentPathLabelGenerator());
        irr.setLabels(labelsList);
        RecordReaderFunction rrf = new RecordReaderFunction(irr);
        JavaRDD<List<Writable>> rdd = origData.map(rrf);
        List<List<Writable>> listSpark = rdd.collect();

        assertEquals(4,listSpark.size());
        for( int i=0; i<4; i++ ){
            assertEquals(1+1, listSpark.get(i).size());
            assertEquals(28*28, ((ArrayWritable)listSpark.get(i).iterator().next()).length());
        }

        //Load normally (i.e., not via Spark), and check that we get the same results (order not withstanding)
        InputSplit is = new FileSplit(new File(folder),new String[]{"bmp"}, true);
//        System.out.println("Locations: " + Arrays.toString(is.locations()));
        irr = new ImageRecordReader(28,28,1,new ParentPathLabelGenerator());
        irr.initialize(is);

        List<List<Writable>> list = new ArrayList<>(4);
        while(irr.hasNext()){
            list.add(irr.next());
        }
        assertEquals(4, list.size());

//        System.out.println("Spark list:");
//        for(List<Writable> c : listSpark ) System.out.println(c);
//        System.out.println("Local list:");
//        for(List<Writable> c : list ) System.out.println(c);

        //Check that each of the values from Spark equals exactly one of the values doing it locally
        boolean[] found = new boolean[4];
        for( int i=0; i<4; i++ ){
            int foundIndex = -1;
            List<Writable> collection = listSpark.get(i);
            for( int j=0; j<4; j++ ){
                if(collection.equals(list.get(j))){
                    if(foundIndex != -1) fail();    //Already found this value -> suggests this spark value equals two or more of local version? (Shouldn't happen)
                    foundIndex = j;
                    if(found[foundIndex]) fail();   //One of the other spark values was equal to this one -> suggests duplicates in Spark list
                    found[foundIndex] = true;   //mark this one as seen before
                }
            }
        }
        int count = 0;
        for( boolean b : found ) if(b) count++;
        assertEquals(4,count);  //Expect all 4 and exactly 4 pairwise matches between spark and local versions
    }

}