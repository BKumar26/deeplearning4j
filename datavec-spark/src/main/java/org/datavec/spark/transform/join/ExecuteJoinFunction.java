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

package org.datavec.spark.transform.join;

import lombok.AllArgsConstructor;
import org.apache.spark.api.java.function.Function;
import org.datavec.api.writable.Writable;
import org.datavec.api.transform.join.Join;
import scala.Tuple2;

import java.util.List;

/**
 * Execute a join
 *
 * @author Alex Black
 */
@AllArgsConstructor
public class ExecuteJoinFunction implements Function<Tuple2<String,Iterable<JoinValue>>, JoinedValue> {

    private Join join;

    @Override
    public JoinedValue call(Tuple2<String, Iterable<JoinValue>> t2) throws Exception {

        //Extract values + check we don't have duplicates...
        JoinValue left = null;
        JoinValue right = null;
        for(JoinValue jv : t2._2()){
            if(jv.isLeft()){
                if(left != null){
                    throw new IllegalStateException("Invalid state: found multiple left values in join with key \"" + t2._1() + "\"");
                }
                left = jv;
            } else {
                if(right != null){
                    throw new IllegalStateException("Invalid state: found multiple right values in join with key \"" + t2._1() + "\"");
                }
                right = jv;
            }
        }
        List<Writable> leftList = (left == null ? null : left.getValues());
        List<Writable> rightList = (right == null ? null : right.getValues());
        List<Writable> joined = join.joinExamples(leftList, rightList);

        return new JoinedValue(left != null, right != null, joined);
    }
}
