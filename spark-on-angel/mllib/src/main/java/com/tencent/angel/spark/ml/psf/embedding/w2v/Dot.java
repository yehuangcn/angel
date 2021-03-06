/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package com.tencent.angel.spark.ml.psf.embedding.w2v;

import com.tencent.angel.PartitionKey;
import com.tencent.angel.exception.AngelException;
import com.tencent.angel.ml.math2.storage.IntFloatDenseVectorStorage;
import com.tencent.angel.ml.matrix.psf.get.base.GetFunc;
import com.tencent.angel.ml.matrix.psf.get.base.GetResult;
import com.tencent.angel.ml.matrix.psf.get.base.PartitionGetParam;
import com.tencent.angel.ml.matrix.psf.get.base.PartitionGetResult;
import com.tencent.angel.spark.ml.psf.embedding.NEDot;
import com.tencent.angel.spark.ml.psf.embedding.ServerWrapper;

import java.util.List;

public class Dot extends GetFunc {

  public Dot(DotParam param) {
    super(param);
  }

  public Dot() { super(null); }

  @Override
  public PartitionGetResult partitionGet(PartitionGetParam partParam) {
    if (partParam instanceof DotPartitionParam) {

      DotPartitionParam param = (DotPartitionParam) partParam;

      // some params
      PartitionKey pkey = param.getPartKey();

      int negative = param.negative;
      int partDim  = param.partDim;
      int window   = param.window;
      int seed     = param.seed;
      int order    = 2;

      // batch sentences
      int[][] sentences = param.sentences;
      // max index for node/word
      int maxIndex = ServerWrapper.getMaxIndex();
      int maxLength = ServerWrapper.getMaxLength();

      // compute number of nodes for one row
      int size = (int) (pkey.getEndCol() - pkey.getStartCol());
      int numNodes = size / (partDim * order);

      int numRows = pkey.getEndRow() - pkey.getStartRow();
      float[][] layers = new float[numRows][];
      for (int row = 0; row < numRows; row ++)
        layers[row] = ((IntFloatDenseVectorStorage) psContext.getMatrixStorageManager()
                .getRow(pkey, row).getSplit().getStorage())
                .getValues();

      EmbeddingModel model;
      switch (param.model) {
        case 0:
          model = new SkipgramModel(partDim, negative, window, seed, maxIndex, numNodes, maxLength, layers);
          break;
        default:
          model = new CbowModel(partDim, negative, window, seed, maxIndex, numNodes, maxLength, layers);
      }

      float[] dots = model.dot(sentences);

      ServerWrapper.setNumInputs(param.partitionId, model.getNumInputsToUpdate());
      ServerWrapper.setNumOutputs(param.partitionId, model.getNumOutputsToUpdate());

      return new DotPartitionResult(dots);
    }

    return null;
  }

  @Override
  public GetResult merge(List<PartitionGetResult> partResults) {
    if (partResults.size() > 0 && partResults.get(0) instanceof DotPartitionResult) {
      int size = ((DotPartitionResult) partResults.get(0)).length;

      // check the length of dot values
      for (PartitionGetResult result: partResults) {
        if (result instanceof DotPartitionResult &&
          size != ((DotPartitionResult) result).length)
          throw new AngelException(
                  String.format("length of dot values not same one is %d other is %d",
                          size,
                          ((DotPartitionResult) result).length));
      }

      // merge dot values from all partitions
      float[] results = new float[size];
      for (PartitionGetResult result: partResults)
        if (result instanceof DotPartitionResult)
          try {
            ((DotPartitionResult) result).merge(results);
          } finally {
            ((DotPartitionResult) result).clear();
          }
      return new NEDot.NEDotResult(results);
    }

    return null;
  }
}
