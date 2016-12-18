/*
 *
 *  * Copyright 2016 Skymind,Inc.
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

package org.deeplearning4j.parallelism;

import org.deeplearning4j.earlystopping.EarlyStoppingConfiguration;
import org.deeplearning4j.earlystopping.EarlyStoppingResult;
import org.deeplearning4j.earlystopping.listener.EarlyStoppingListener;
import org.deeplearning4j.earlystopping.scorecalc.ScoreCalculator;
import org.deeplearning4j.earlystopping.termination.EpochTerminationCondition;
import org.deeplearning4j.earlystopping.termination.IterationTerminationCondition;
import org.deeplearning4j.earlystopping.trainer.IEarlyStoppingTrainer;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Conduct parallel early stopping training with ParallelWrapper under the hood.<br>
 * Can be used to train a {@link MultiLayerNetwork} or a {@link ComputationGraph} via early stopping.
 *
 * @author Justin Long (crockpotveggies)
 */
public class EarlyStoppingParallelTrainer<T extends Model> implements IEarlyStoppingTrainer<T> {

    private static Logger log = LoggerFactory.getLogger(EarlyStoppingParallelTrainer.class);

    protected T model;

    protected final EarlyStoppingConfiguration<T> esConfig;
    private final DataSetIterator train;
    private final MultiDataSetIterator trainMulti;
    private final Iterator<?> iterator;
    private EarlyStoppingListener<T> listener;
    private ParallelWrapper wrapper;
    private double bestModelScore = Double.MAX_VALUE;
    private int bestModelEpoch = -1;
    private double latestScore = 0.0;
    private boolean terminate = false;
    private int iterCount = 0;
    protected IterationTerminationCondition terminationReason = null;

    public EarlyStoppingParallelTrainer(EarlyStoppingConfiguration<T> earlyStoppingConfiguration, T model,
                                        DataSetIterator train, MultiDataSetIterator trainMulti,
                                        int workers, int prefetchBuffer, int averagingFrequency) {
        this(earlyStoppingConfiguration, model, train, trainMulti, null, workers, prefetchBuffer, averagingFrequency, true, true);
    }

    public EarlyStoppingParallelTrainer(EarlyStoppingConfiguration<T> earlyStoppingConfiguration, T model,
                                        DataSetIterator train, MultiDataSetIterator trainMulti, EarlyStoppingListener<T> listener,
                                        int workers, int prefetchBuffer, int averagingFrequency) {
        this(earlyStoppingConfiguration, model, train, trainMulti, listener, workers, prefetchBuffer, averagingFrequency, true, true);
    }

    public EarlyStoppingParallelTrainer(EarlyStoppingConfiguration<T> earlyStoppingConfiguration, T model,
                                           DataSetIterator train, MultiDataSetIterator trainMulti, EarlyStoppingListener<T> listener,
                                           int workers, int prefetchBuffer, int averagingFrequency, boolean reportScoreAfterAveraging, boolean useLegacyAveraging) {
        this.esConfig = earlyStoppingConfiguration;
        this.train = train;
        this.trainMulti = trainMulti;
        this.iterator = (train != null ? train : trainMulti);
        this.listener = listener;
        this.model = model;
        this.wrapper = new ParallelWrapper.Builder<T>(model)
            .workers(workers).prefetchBuffer(prefetchBuffer)
            .averagingFrequency(averagingFrequency).useLegacyAveraging(useLegacyAveraging)
            .reportScoreAfterAveraging(reportScoreAfterAveraging).build();
    }

    protected synchronized void setTerminationReason(IterationTerminationCondition terminationReason) {
        this.terminationReason = terminationReason;
    }

    @Override
    public synchronized EarlyStoppingResult<T> fit() {
        log.info("Starting early stopping training");
        if (esConfig.getScoreCalculator() == null)
            log.warn("No score calculator provided for early stopping. Score will be reported as 0.0 to epoch termination conditions");

        //Initialize termination conditions:
        if (esConfig.getIterationTerminationConditions() != null) {
            for (IterationTerminationCondition c : esConfig.getIterationTerminationConditions()) {
                c.initialize();
            }
        }
        if (esConfig.getEpochTerminationConditions() != null) {
            for (EpochTerminationCondition c : esConfig.getEpochTerminationConditions()) {
                c.initialize();
            }
        }

        if (listener != null) {
            listener.onStart(esConfig, model);
        }

        Map<Integer, Double> scoreVsEpoch = new LinkedHashMap<>();

        // append the iteration listener
        int epochCount = 0;
        AveragingIterationListener trainerListener = new AveragingIterationListener(this);

        if (model instanceof MultiLayerNetwork) {
            Collection<IterationListener> listeners = ((MultiLayerNetwork) model).getListeners();
            List<IterationListener> newListeners = new LinkedList<>(listeners);
            newListeners.add(trainerListener);
            ((MultiLayerNetwork) model).setListeners(newListeners);
        } else if (model instanceof ComputationGraph) {
            Collection<IterationListener> listeners = ((ComputationGraph) model).getListeners();
            List<IterationListener> newListeners = new LinkedList<>(listeners);
            newListeners.add(trainerListener);
            ((ComputationGraph) model).setListeners(newListeners);
        }

        // iterate through epochs
        while (true) {
            // note that we don't call train.reset() because ParallelWrapper does it already
            try {
                if (train != null) {
                    wrapper.fit(train);
                } else
                    wrapper.fit(trainMulti);
            } catch (Exception e) {
                log.warn("Early stopping training terminated due to exception at epoch {}, iteration {}",
                    epochCount, iterCount, e);
                //Load best model to return
                T bestModel;
                try {
                    bestModel = esConfig.getModelSaver().getBestModel();
                } catch (IOException e2) {
                    throw new RuntimeException(e2);
                }
                return new EarlyStoppingResult<>(
                    EarlyStoppingResult.TerminationReason.Error,
                    e.toString(),
                    scoreVsEpoch,
                    bestModelEpoch,
                    bestModelScore,
                    epochCount,
                    bestModel);
            }

            if (terminate) {
                //Handle termination condition:
                log.info("Hit per iteration epoch termination condition at epoch {}, iteration {}. Reason: {}",
                        epochCount, iterCount, terminationReason);

                if (esConfig.isSaveLastModel()) {
                    //Save last model:
                    try {
                        esConfig.getModelSaver().saveLatestModel(model, 0.0);
                    } catch (IOException e) {
                        throw new RuntimeException("Error saving most recent model", e);
                    }
                }

                T bestModel;
                try {
                    bestModel = esConfig.getModelSaver().getBestModel();
                } catch (IOException e2) {
                    throw new RuntimeException(e2);
                }

                wrapper.shutdown();

                EarlyStoppingResult<T> result = new EarlyStoppingResult<>(
                        EarlyStoppingResult.TerminationReason.IterationTerminationCondition,
                        terminationReason.toString(),
                        scoreVsEpoch,
                        bestModelEpoch,
                        bestModelScore,
                        epochCount,
                        bestModel);
                if (listener != null) {
                    listener.onCompletion(result);
                }
                return result;
            }

            log.info("Completed training epoch {}", epochCount);


            if ((epochCount == 0 && esConfig.getEvaluateEveryNEpochs() == 1) || epochCount % esConfig.getEvaluateEveryNEpochs() == 0) {
                //Calculate score at this epoch:
                ScoreCalculator sc = esConfig.getScoreCalculator();
                double score = (sc == null ? 0.0 : esConfig.getScoreCalculator().calculateScore(model));
                scoreVsEpoch.put(epochCount - 1, score);

                if (sc != null && score < bestModelScore) {
                    //Save best model:
                    if (bestModelEpoch == -1) {
                        //First calculated/reported score
                        log.info("Score at epoch {}: {}", epochCount, score);
                    } else {
                        log.info("New best model: score = {}, epoch = {} (previous: score = {}, epoch = {})",
                                score, epochCount, bestModelScore, bestModelEpoch);
                    }
                    bestModelScore = score;
                    bestModelEpoch = epochCount;

                    try {
                        esConfig.getModelSaver().saveBestModel(model, score);
                    } catch (IOException e) {
                        throw new RuntimeException("Error saving best model", e);
                    }
                }

                if (esConfig.isSaveLastModel()) {
                    //Save last model:
                    try {
                        esConfig.getModelSaver().saveLatestModel(model, score);
                    } catch (IOException e) {
                        throw new RuntimeException("Error saving most recent model", e);
                    }
                }

                if (listener != null) {
                    listener.onEpoch(epochCount, score, esConfig, model);
                }

                //Check per-epoch termination conditions:
                boolean epochTerminate = false;
                EpochTerminationCondition termReason = null;
                for (EpochTerminationCondition c : esConfig.getEpochTerminationConditions()) {
                    if (c.terminate(epochCount, score)) {
                        epochTerminate = true;
                        termReason = c;
                        break;
                    }
                }
                if (epochTerminate) {
                    log.info("Hit epoch termination condition at epoch {}. Details: {}", epochCount, termReason.toString());
                    T bestModel;
                    try {
                        bestModel = esConfig.getModelSaver().getBestModel();
                    } catch (IOException e2) {
                        throw new RuntimeException(e2);
                    }
                    EarlyStoppingResult<T> result = new EarlyStoppingResult<>(
                            EarlyStoppingResult.TerminationReason.EpochTerminationCondition,
                            termReason.toString(),
                            scoreVsEpoch,
                            bestModelEpoch,
                            bestModelScore,
                            epochCount + 1,
                            bestModel);
                    if (listener != null) {
                        listener.onCompletion(result);
                    }

                    wrapper.shutdown();
                    return result;
                }
            }
            epochCount++;

        }
    }

    public synchronized void setLatestScore(double latestScore) {
        this.latestScore = latestScore;
    }

    public synchronized void incrementIteration() {
        iterCount++;
    }

    public synchronized void setTermination(boolean terminate) {
        this.terminate = terminate;
    }

    public synchronized boolean getTermination() { return terminate; }

    /**
     * AveragingIterationListener is attached to the primary model within ParallelWrapper. It is invoked
     * with each averaging step, and thus averaging is considered analogous to an iteration.
     * @param <T>
     */
    private class AveragingIterationListener<T extends Model> implements IterationListener {
        private final Logger log = LoggerFactory.getLogger(AveragingIterationListener.class);
        private boolean invoked = false;
        private IterationTerminationCondition terminationReason = null;
        private EarlyStoppingParallelTrainer<T> trainer;

        /** Default constructor printing every 10 iterations */
        public AveragingIterationListener(EarlyStoppingParallelTrainer<T> trainer) {
            this.trainer = trainer;
        }

        @Override
        public boolean invoked(){ return invoked; }

        @Override
        public void invoke() { this.invoked = true; }

        @Override
        public void iterationDone(Model model, int iteration) {
            invoke();
            //Check per-iteration termination conditions
            double latestScore = model.score();
            trainer.setLatestScore(latestScore);
            for (IterationTerminationCondition c : esConfig.getIterationTerminationConditions()) {
                if (c.terminate(latestScore)) {
                    trainer.setTermination(true);
                    trainer.setTerminationReason(c);
                    break;
                }
            }
            if (trainer.getTermination()) {
                wrapper.shutdown();
            }
            System.out.println(latestScore);

            trainer.incrementIteration();
        }
    }

    @Override
    public void setListener(EarlyStoppingListener<T> listener) {
        this.listener = listener;
    }

    protected void reset() {
        if (train != null) {
            train.reset();
        }
        if (trainMulti != null) {
            trainMulti.reset();
        }
    }


}