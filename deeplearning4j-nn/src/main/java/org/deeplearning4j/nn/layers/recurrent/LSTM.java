/*-
 *
 *  * Copyright 2015 Skymind,Inc.
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
 *
 */

package org.deeplearning4j.nn.layers.recurrent;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.conf.CacheMode;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.params.LSTMParamInitializer;
import org.deeplearning4j.util.OneTimeLogger;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import java.util.Map;
import java.util.Properties;

/**
 * LSTM layer implementation.
 *
 * See also for full/vectorized equations (and a comparison to other LSTM variants):
 * Greff et al. 2015, "LSTM: A Search Space Odyssey", pg11. This is the "no peephole" variant in said paper
 * http://arxiv.org/pdf/1503.04069.pdf
 *
 * @author Alex Black
 * @see GravesLSTM GravesLSTM class, for the version with peephole connections
 */
@Slf4j
public class LSTM extends BaseRecurrentLayer<org.deeplearning4j.nn.conf.layers.LSTM> {
    public static final String STATE_KEY_PREV_ACTIVATION = "prevAct";
    public static final String STATE_KEY_PREV_MEMCELL = "prevMem";
    protected LSTMHelper helper = null;
    protected FwdPassReturn cachedFwdPass;

    public LSTM(NeuralNetConfiguration conf) {
        super(conf);
        initializeHelper();
    }

    public LSTM(NeuralNetConfiguration conf, INDArray input) {
        super(conf, input);
        initializeHelper();
    }

    void initializeHelper() {
        try {
            helper = Class.forName("org.deeplearning4j.nn.layers.recurrent.CudnnLSTMHelper")
                            .asSubclass(LSTMHelper.class).newInstance();
            log.debug("CudnnLSTMHelper successfully initialized");
            if (!helper.checkSupported(layerConf().getGateActivationFn(), layerConf().getActivationFn(), false)) {
                helper = null;
            }
        } catch (Throwable t) {
            if (!(t instanceof ClassNotFoundException)) {
                log.warn("Could not initialize CudnnLSTMHelper", t);
            } else {
                Properties p = Nd4j.getExecutioner().getEnvironmentInformation();
                if (p.getProperty("backend").equals("CUDA")) {
                    OneTimeLogger.info(log, "cuDNN not found: "
                                    + "use cuDNN for better GPU performance by including the deeplearning4j-cuda module. "
                                    + "For more information, please refer to: https://deeplearning4j.org/cudnn", t);
                }
            }
        }
    }

    @Override
    public Gradient gradient() {
        throw new UnsupportedOperationException(
                        "gradient() method for layerwise pretraining: not supported for LSTMs (pretraining not possible) "
                                        + layerId());
    }

    @Override
    public Gradients backpropGradient(INDArray epsilon) {
        return backpropGradientHelper(epsilon, false, -1);
    }

    @Override
    public Gradients tbpttBackpropGradient(INDArray epsilon, int tbpttBackwardLength) {
        return backpropGradientHelper(epsilon, true, tbpttBackwardLength);
    }


    private Gradients backpropGradientHelper(final INDArray epsilon, final boolean truncatedBPTT,
                    final int tbpttBackwardLength) {

        final INDArray inputWeights = getParamWithNoise(LSTMParamInitializer.INPUT_WEIGHT_KEY, true);
        final INDArray recurrentWeights = getParamWithNoise(LSTMParamInitializer.RECURRENT_WEIGHT_KEY, true); //Shape: [hiddenLayerSize,4*hiddenLayerSize+3]; order: [wI,wF,wO,wG,wFF,wOO,wGG]

        //First: Do forward pass to get gate activations, zs etc.
        FwdPassReturn fwdPass;
        if (truncatedBPTT) {
            fwdPass = activateHelper(true, stateMap.get(STATE_KEY_PREV_ACTIVATION),
                            stateMap.get(STATE_KEY_PREV_MEMCELL), true);
            //Store last time step of output activations and memory cell state in tBpttStateMap
            tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION, fwdPass.lastAct.leverageTo(ComputationGraph.workspaceTBPTT));
            tBpttStateMap.put(STATE_KEY_PREV_MEMCELL, fwdPass.lastMemCell.leverageTo(ComputationGraph.workspaceTBPTT));
        } else {
            fwdPass = activateHelper(true, null, null, true);
        }


        Pair<Gradient,INDArray> p = LSTMHelpers.backpropGradientHelper(this.conf, this.layerConf().getGateActivationFn(), this.input,
                        recurrentWeights, inputWeights, epsilon, truncatedBPTT, tbpttBackwardLength, fwdPass, true,
                        LSTMParamInitializer.INPUT_WEIGHT_KEY, LSTMParamInitializer.RECURRENT_WEIGHT_KEY,
                        LSTMParamInitializer.BIAS_KEY, gradientViews, null, false, helper);

        weightNoiseParams.clear();
        return p;
    }

    @Override
    public INDArray activate(INDArray input, boolean training) {
        setInput(input);
        return activateHelper(training, null, null, false).fwdPassOutput;
    }

    @Override
    public INDArray activate(boolean training) {
        return activateHelper(training, null, null, false).fwdPassOutput;
    }

    private FwdPassReturn activateHelper(final boolean training, final INDArray prevOutputActivations,
                    final INDArray prevMemCellState, boolean forBackprop) {

        if (cacheMode == null)
            cacheMode = CacheMode.NONE;

        if (forBackprop && cachedFwdPass != null) {
            FwdPassReturn ret = cachedFwdPass;
            cachedFwdPass = null;
            return ret;
        }

        final INDArray recurrentWeights = getParamWithNoise(LSTMParamInitializer.RECURRENT_WEIGHT_KEY, training); //Shape: [hiddenLayerSize,4*hiddenLayerSize+3]; order: [wI,wF,wO,wG,wFF,wOO,wGG]
        final INDArray inputWeights = getParamWithNoise(LSTMParamInitializer.INPUT_WEIGHT_KEY, training); //Shape: [n^(L-1),4*hiddenLayerSize]; order: [wi,wf,wo,wg]
        final INDArray biases = getParamWithNoise(LSTMParamInitializer.BIAS_KEY, training); //by row: IFOG			//Shape: [4,hiddenLayerSize]; order: [bi,bf,bo,bg]^T

        FwdPassReturn fwd = LSTMHelpers.activateHelper(this, this.conf, this.layerConf().getGateActivationFn(),
                        this.input, recurrentWeights, inputWeights, biases, training, prevOutputActivations,
                        prevMemCellState, (training && cacheMode != CacheMode.NONE) || forBackprop, true,
                        LSTMParamInitializer.INPUT_WEIGHT_KEY, null, false, helper,
                        forBackprop ? cacheMode : CacheMode.NONE);

        if (training && cacheMode != CacheMode.NONE) {
            cachedFwdPass = fwd;
        }

        return fwd;
    }

    @Override
    public boolean isPretrainLayer() {
        return false;
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState,
                    int minibatchSize) {
        //LSTM (standard, not bi-directional) don't make any changes to the data OR the mask arrays
        //Any relevant masking occurs during backprop
        //They also set the current mask array as inactive: this is for situations like the following:
        // in -> dense -> lstm -> dense -> lstm
        // The first dense should be masked using the input array, but the second shouldn't. If necessary, the second
        // dense will be masked via the output layer mask

        return new Pair<>(maskArray, MaskState.Passthrough);
    }

    @Override
    public INDArray rnnTimeStep(INDArray input) {
        setInput(input);
        FwdPassReturn fwdPass = activateHelper(false, stateMap.get(STATE_KEY_PREV_ACTIVATION),
                        stateMap.get(STATE_KEY_PREV_MEMCELL), false);
        INDArray outAct = fwdPass.fwdPassOutput;
        //Store last time step of output activations and memory cell state for later use:
        stateMap.put(STATE_KEY_PREV_ACTIVATION, fwdPass.lastAct);
        stateMap.put(STATE_KEY_PREV_MEMCELL, fwdPass.lastMemCell);

        return outAct;
    }



    @Override
    public INDArray rnnActivateUsingStoredState(INDArray input, boolean training, boolean storeLastForTBPTT) {
        setInput(input);
        FwdPassReturn fwdPass = activateHelper(training, stateMap.get(STATE_KEY_PREV_ACTIVATION),
                        stateMap.get(STATE_KEY_PREV_MEMCELL), false);
        INDArray outAct = fwdPass.fwdPassOutput;
        if (storeLastForTBPTT) {
            //Store last time step of output activations and memory cell state in tBpttStateMap
            tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION, fwdPass.lastAct.leverageTo(ComputationGraph.workspaceTBPTT));
            tBpttStateMap.put(STATE_KEY_PREV_MEMCELL, fwdPass.lastMemCell.leverageTo(ComputationGraph.workspaceTBPTT));
        }

        return outAct;
    }
}