import abc
import os
import subprocess
import zipfile
from urllib.request import urlretrieve
import timeit
import numpy as np

import hail as hl


def resource(filename):
    assert _initialized
    return os.path.join(_data_dir, filename)


def get_mt():
    return _mt


def benchmark(f):
    _registry[f.__name__] = Benchmark(f, f.__name__)
    return f


class Benchmark(object):
    def __init__(self, f, name):
        self.name = name
        self.f = f

    def run(self):
        self.f()


_registry = {}
_data_dir = ''
_mt = None
_initialized = False
_n_iter = None


def download_data():
    global _initialized, _data_dir, _mt
    _data_dir = os.environ.get('HAIL_BENCHMARK_DIR', '/tmp/hail_benchmark_data')
    print(f'using benchmark data directory {_data_dir}')
    os.makedirs(_data_dir, exist_ok=True)

    files = map(lambda f: os.path.join(_data_dir, f), ['profile.vcf.bgz',
                                                       'profile.mt',
                                                       'table_10M_par_1000.ht',
                                                       'table_10M_par_100.ht',
                                                       'table_10M_par_10.ht',
                                                       'gnomad_dp_simulation.mt'])
    if not all(os.path.exists(file) for file in files):
        vcf = os.path.join(_data_dir, 'profile.vcf.bgz')
        print('files not found - downloading...', end='', flush=True)
        urlretrieve('https://storage.googleapis.com/hail-common/benchmark/profile.vcf.bgz',
                    os.path.join(_data_dir, vcf))
        print('done', flush=True)
        print('importing...', end='', flush=True)
        hl.import_vcf(vcf).write(os.path.join(_data_dir, 'profile.mt'), overwrite=True)

        ht = hl.utils.range_table(10_000_000, 1000).annotate(**{f'f_{i}': hl.rand_unif(0, 1) for i in range(5)})
        ht = ht.checkpoint(os.path.join(_data_dir, 'table_10M_par_1000.ht'), overwrite=True)
        ht = ht.naive_coalesce(100).checkpoint(os.path.join(_data_dir, 'table_10M_par_100.ht'), overwrite=True)
        ht.naive_coalesce(10).write(os.path.join(_data_dir, 'table_10M_par_10.ht'), overwrite=True)

        mt = hl.utils.range_matrix_table(n_rows=250_000, n_cols=1_000, n_partitions=32)
        mt = mt.annotate_entries(x = hl.int(hl.rand_unif(0, 4.5) ** 3))
        mt.write(os.path.join(_data_dir, 'gnomad_dp_simulation.mt'))

        print('done', flush=True)
    else:
        print('all files found.', flush=True)

    _initialized = True
    _mt = hl.read_matrix_table(resource('profile.mt'))


def _ensure_initialized():
    if not _initialized:
        raise AssertionError("Hail benchmark environment not initialized. "
                             "Are you running benchmark from the main module?")


def initialize(cores, log, n_iter):
    assert not _initialized
    hl.init(master=f'local[{cores}]', quiet=True, log=log)

    global _n_iter
    _n_iter = n_iter

    download_data()

    # make JVM do something to ensure that it is fresh
    hl.utils.range_table(1)._force_count()


def _run(benchmark, n_iter):
    print(f'running {benchmark.name}...')
    times = []
    for i in range(n_iter):
        time = timeit.Timer(lambda: benchmark.run()).timeit(1)
        times.append(time)
        print(f'    run {i + 1} took {time:.2f}s')
    print(f'    Mean, Median: {np.mean(times):.2f}s, {np.median(times):.2f}s')


def run_all():
    _ensure_initialized()
    for name, benchmark in _registry.items():
        _run(benchmark, _n_iter)


def run_pattern(pat):
    _ensure_initialized()
    test_run = False
    for name, benchmark in _registry.items():
        if pat in name:
            test_run = True
            _run(benchmark, _n_iter)
    if not test_run:
        raise ValueError(f'pattern {pat!r} matched no benchmarks')


def run_single(name):
    _ensure_initialized()

    if name not in _registry:
        raise ValueError(f'test {name!r} not found')
    else:
        _run(_registry[name], _n_iter)
