package hex.rulefit;

import hex.*;
import hex.glm.GLMModel;
import hex.util.LinearAlgebraUtils;
import water.*;
import water.fvec.Frame;
import water.udf.CFuncRef;
import water.util.TwoDimTable;


public class RuleFitModel extends Model<RuleFitModel, RuleFitModel.RuleFitParameters, RuleFitModel.RuleFitOutput> {
    public enum Algorithm {DRF, GBM, AUTO}

    public enum ModelType {RULES, RULES_AND_LINEAR, LINEAR}

    @Override
    public ToEigenVec getToEigenVec() {
        return LinearAlgebraUtils.toEigen;
    }

    GLMModel glmModel;

    RuleEnsemble ruleEnsemble;
    
    public static class RuleFitParameters extends Model.Parameters {
        public String algoName() {
            return "RuleFit";
        }

        public String fullName() {
            return "RuleFit";
        }

        public String javaName() {
            return RuleFitModel.class.getName();
        }

        @Override
        public long progressUnits() {
            return RuleFit.WORK_TOTAL;
        }

        // the algorithm to use to generate rules. Options are "DRF" (default), "GBM"
        public Algorithm _algorithm = Algorithm.AUTO;

        // minimum length of rules. Defaults to 3.
        public int _min_rule_length = 3;

        // maximum length of rules. Defaults to 3.
        public int _max_rule_length = 3;

        // the maximum number of rules to return. Defaults to -1 which means the number of rules is selected 
        // by diminishing returns in model deviance.
        public int _max_num_rules = -1;

        // specifies type of base learners in the ensemble. Options are RULES_AND_LINEAR (initial ensemble includes both rules and linear terms, default), RULES (prediction rules only), LINEAR (linear terms only)
        public ModelType _model_type = ModelType.RULES_AND_LINEAR;
        
        // specifies the number of trees to build in the tree model. Defaults to 50.
        public int _rule_generation_ntrees = 50;
    }

    public static class RuleFitOutput extends Model.Output {

        // a set of rules and coefficients

        public double[] _intercept;

        public TwoDimTable _rule_importance = null;

        Key[] treeModelsKeys = null;

        Key glmModelKey = null;

        //  feature interactions ...

        public RuleFitOutput(RuleFit b) {
            super(b);
        }
    }

    public RuleFitModel(Key<RuleFitModel> selfKey, RuleFitParameters parms, RuleFitOutput output, GLMModel glmModel, RuleEnsemble ruleEnsemble) {
        super(selfKey, parms, output);
        this.glmModel = glmModel;
        this.ruleEnsemble = ruleEnsemble;
    }

    @Override
    public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
        assert domain == null;
        switch (_output.getModelCategory()) {
            case Binomial:
                return new ModelMetricsBinomial.MetricBuilderBinomial(domain);
            case Multinomial:
                return new ModelMetricsMultinomial.MetricBuilderMultinomial(_output.nclasses(), domain);
            case Regression:
                return new ModelMetricsRegression.MetricBuilderRegression();
            default:
                throw H2O.unimpl("Invalid ModelCategory " + _output.getModelCategory());
        }
    }

    @Override
    protected double[] score0(double data[], double preds[]) {
        throw new UnsupportedOperationException("RuleFitModel doesn't support scoring on raw data. Use score() instead.");
    }

    @Override
    public Frame score(Frame fr, String destination_key, Job j, boolean computeMetrics, CFuncRef customMetricFunc) throws IllegalArgumentException {
        Frame adaptFr = new Frame(fr);
        adaptTestForTrain(adaptFr, true, false);
        Frame pathsFrame = new Frame(Key.make("paths_frame" + destination_key));
        if (ModelType.RULES_AND_LINEAR.equals(this._parms._model_type) || ModelType.RULES.equals(this._parms._model_type)) {
            Frame frame = new Frame(Key.make());
            // put response and weights last
            for (String name : adaptFr.names()) {
                if (!_parms._response_column.equals(name) &&  
                        (_parms._weights_column == null || (_parms._weights_column != null && !_parms._weights_column.equals(name))) &&
                        (_parms._ignored_columns == null || (_parms._ignored_columns != null && !_parms._ignored_columns.equals(name)))) {
                    frame.add(name, adaptFr.vec(name));
                }
            }
            if (adaptFr.vec(_parms._response_column) != null)
                frame.add(_parms._response_column,fr.vec(_parms._response_column));
            if (_parms._weights_column != null && fr.vec(_parms._weights_column) != null)
                frame.add(_parms._weights_column,fr.vec(_parms._weights_column));
            
            pathsFrame.add(ruleEnsemble.createGLMTrainFrame(frame, _parms._max_rule_length - _parms._min_rule_length + 1, _parms._rule_generation_ntrees));
        }
        if (ModelType.RULES_AND_LINEAR.equals(this._parms._model_type) || ModelType.LINEAR.equals(this._parms._model_type)) {
            adaptFr = new Frame(fr.deepCopy(null));
            adaptTestForTrain(adaptFr, true, false);
            adaptFr.setNames(RuleFitUtils.getLinearNames(adaptFr.numCols(), adaptFr.names()));
            pathsFrame.add(adaptFr);
        }
        GLMModel glmModel = DKV.getGet(_output.glmModelKey);
        Frame destination = glmModel.score(pathsFrame, destination_key, null, true);

        updateModelMetrics(glmModel, fr);
        
        pathsFrame.remove();
        return destination;
    }

    @Override
    protected Futures remove_impl(Futures fs, boolean cascade) {
        super.remove_impl(fs, cascade);
        if(cascade) {
            glmModel.remove(fs);
        }
        
        return fs;
    }

    void updateModelMetrics( GLMModel glmModel, Frame fr){
        this._output._validation_metrics = glmModel._output._validation_metrics;
        this._output._training_metrics = glmModel._output._training_metrics;
        this._output._cross_validation_metrics = glmModel._output._cross_validation_metrics;
        this._output._cross_validation_metrics_summary = glmModel._output._cross_validation_metrics_summary;

        for (Key<ModelMetrics> modelMetricsKey : glmModel._output.getModelMetrics()) {
            this.addModelMetrics(modelMetricsKey.get().deepCloneWithDifferentModelAndFrame(this, fr));
        }
    }
}
