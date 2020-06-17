import warnings
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Dict, Tuple, Optional

import numpy as np
from loguru import logger
from loguru import logger as logging
from umap import UMAP

from .metrics import global_score, sammon_error, stability_score, auc_score, intristic_multiscale_score, \
    clustering_score
from .utils import DEFAULT_PARAMETERS, VisMetrics, AVAILBALE_VIS_METRICS


class Transformer(ABC):
    def __init__(self, parameters: Dict):
        self.__set_params__(parameters)
        self.__instance__, self.transformer = self.__create__()  # clear instance of transformer (not fitted into data)

    @abstractmethod
    def __set_params__(self, parameters: Dict):
        pass

    @abstractmethod
    def __create__(self):
        return None, None

    @abstractmethod
    def fit(self, X: np.ndarray, y=None):
        pass

    @abstractmethod
    def transform(self, X: np.ndarray, y=None) -> np.ndarray:
        return None

    @abstractmethod
    def fit_transform(self, X: np.ndarray, y=None) -> np.ndarray:
        return None

    def eval(self, X: np.ndarray, _X: np.ndarray, y=None,
             evaluation_metrics: Tuple[VisMetrics] = tuple(AVAILBALE_VIS_METRICS),
             _auc_cv=5) -> Dict[str, str]:
        """
        Evaluates vizualization using listed evaluation_metrics names
        :param X: original points
        :param _X: transformed points
        :param y: labels
        :param evaluation_metrics: list of metrics names
        :param _auc_cv: number of splits for acc evaluation
        :return: dict of metric values
        """
        start = datetime.now()
        eval_metrics = {}
        if VisMetrics.global_score in evaluation_metrics:
            eval_metrics[VisMetrics.global_score.name] = str(global_score(X, _X))
        if VisMetrics.sammon_error in evaluation_metrics:
            eval_metrics[VisMetrics.sammon_error.name] = str(sammon_error(X, _X))
        if VisMetrics.auc_score in evaluation_metrics and y is not None:
            acc_result = auc_score(_X, y)
            eval_metrics['knn_acc'] = str(acc_result['knn_acc'])  # TODO knn_acc
            eval_metrics['svc_acc'] = str(acc_result['svc_acc'])
        if VisMetrics.stability in evaluation_metrics:
            eval_metrics[VisMetrics.stability.name] = str(stability_score(X, self.__instance__))
        if VisMetrics.msid in evaluation_metrics:
            eval_metrics[VisMetrics.msid.name] = str(intristic_multiscale_score(X, _X))
        if VisMetrics.clustering in evaluation_metrics and y is not None:
            ars, ami = clustering_score(_X, y)
            eval_metrics['clustering_random_score'] = str(ars)  # TODO svc_acc
            eval_metrics['clustering_mutual_info'] = str(ami)
        logging.info(f'Evaluation of embeddings took {datetime.now() - start}')
        return eval_metrics


class UmapTransformer(Transformer):
    def __init__(self, parameters):
        self.default_parameters = DEFAULT_PARAMETERS['umap']
        super().__init__(parameters)
        self.embedding_ = None

    def __set_params__(self, parameters):
        self.min_dist = parameters.get('min_dist', self.default_parameters['min_dist'])
        self.n_neighbours = parameters.get('n_neighbours', self.default_parameters['n_neighbours'])
        self.metric = parameters.get('metric', self.default_parameters['metric'])
        self.n_components = parameters.get('n_components', self.default_parameters['n_components'])
        self.use_labels = parameters.get('use_labels', False)

    def __create__(self):
        transformer = UMAP(n_neighbors=self.n_neighbours,
                           n_components=self.n_components,
                           min_dist=self.min_dist,
                           metric=self.metric)
        __instance__ = UMAP(n_neighbors=self.n_neighbours,  # unfitted instance with same parameters for
                            n_components=self.n_components,  # stability metric
                            min_dist=self.min_dist,
                            metric=self.metric)
        return transformer, __instance__

    def fit(self, X, y=None):
        warnings.filterwarnings('ignore')
        if y is None:
            self.transformer.fit(X)
        elif self.use_labels:
            self.transformer.fit(X, y)
        else:
            self.transformer.fit(X)
        self.embedding_ = self.transformer.embedding_

    def fit_transform(self, X, y=None):
        warnings.filterwarnings('ignore')
        if self.use_labels:
            _X = self.transformer.fit_transform(X, y)
        else:
            _X = self.transformer.fit_transform(X)
        self.embedding_ = _X
        return _X

    def transform(self, X, y=None):
        warnings.filterwarnings('ignore')
        if self.use_labels or y is not None:
            _X = self.transformer.transform(X, y)
        else:
            _X = self.transformer.transform(X)
        return _X


def transform_high_dimensional(method, parameters,
                               training_embeddings: Optional[np.ndarray],
                               production_embeddings: np.ndarray,
                               transformer_instance: Optional[Transformer] = None,
                               vis_metrics: Tuple[VisMetrics] = tuple(AVAILBALE_VIS_METRICS)):
    """
    Transforms data from higher dimensions to lower
    :param method: transformer method
    :param parameters: {}
    :param training_embeddings:
    :param production_embeddings:
    :param transformer_instance:
    :param vis_metrics:
    :return: (embedding dict, transformer)
    """
    logging.info('Transform high dimensional')
    result = {}
    transformer = None
    need_fit = True

    if method == 'umap':
        if transformer_instance and isinstance(transformer_instance, UmapTransformer):
            need_fit = False
            transformer = transformer_instance
        else:
            transformer = UmapTransformer(parameters)
    if transformer is None:
        logger.error('Cannot define transformer. Illegal method name')

    training_embeddings = training_embeddings
    if training_embeddings is not None and production_embeddings is not None:
        training_embeddings_sample_size = min(5000, training_embeddings.shape[0])
        training_embeddings = training_embeddings[
            np.random.choice(training_embeddings.shape[0], training_embeddings_sample_size, replace=False)]
        total_embeddings = np.concatenate([production_embeddings, training_embeddings])
    else:
        total_embeddings = production_embeddings

    start = datetime.now()
    if not need_fit and method == 'umap':
        plottable_embeddings = transformer.transform(production_embeddings)
    else:
        plottable_embeddings = transformer.fit_transform(
            total_embeddings)  # TODO add ground truth labels management for semi-supervised umap

    logger.info(
        f'Fitting {total_embeddings.shape[0]} {total_embeddings.shape[1]}-dimensional points took '
        f'{datetime.now() - start}')

    vis_eval_metrics = transformer.eval(total_embeddings[:len(plottable_embeddings)], plottable_embeddings,
                                        y=None,
                                        evaluation_metrics=vis_metrics)  # TODO add ground truth_labels

    plottable_embeddings = plottable_embeddings[:len(production_embeddings)]  # Slice excessive ?

    result['data_shape'] = plottable_embeddings.shape
    result['data'] = plottable_embeddings.tolist()
    result['visualization_metrics'] = vis_eval_metrics

    transformer.embedding_ = None  # ?

    return result, transformer
