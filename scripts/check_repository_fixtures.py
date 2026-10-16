"""微型仓库的独立 Java 21 对照验证；不调用模型或网络。"""
import argparse
import errno
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from repository_fixtures import TASKS


def prepare_workspace(task, workspace):
    """仅以规范 UTF-8/LF 字节写入公开源码；不复制隐藏断言或参考修改。"""
    for name, source in task['sources'].items():
        target = Path(workspace) / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.encode('utf-8'))


def verify_workspace(task, workspace, java, javac, timeout=20):
    """精确读取声明的文件快照，保留换行字节以验证未修改源码。"""
    try:
        workspace = Path(workspace)
        declared = set(task['sources'])
        canonical_workspace = workspace.resolve()
        if workspace.is_symlink():
            return {'passed': False, 'phase': 'snapshot', 'reason': 'redirected_source_path'}
        for name in declared:
            relative = Path(name)
            source = workspace / relative
            if (relative.is_absolute() or '..' in relative.parts or source.is_symlink()
                    or source.resolve() != canonical_workspace / relative):
                return {'passed': False, 'phase': 'snapshot', 'reason': 'redirected_source_path'}
        # 枚举前检查每个条目，防止链接目录被递归枚举悄悄漏掉。
        actual = set()
        for file in workspace.rglob('*'):
            relative = file.relative_to(workspace)
            if file.is_symlink() or file.resolve() != canonical_workspace / relative:
                return {'passed': False, 'phase': 'snapshot', 'reason': 'redirected_source_path'}
            # 允许运行时会话文件，但不允许额外的 Java 源文件。
            if file.suffix.lower() == '.java':
                actual.add(relative.as_posix())
        if actual != declared:
            return {'passed': False, 'phase': 'snapshot', 'reason': 'declared_source_set_mismatch'}
        sources = {name: (workspace / name).read_bytes().decode('utf-8') for name in task['sources']}
    except (OSError, UnicodeError, ValueError, RuntimeError):
        return {'passed': False, 'phase': 'snapshot', 'reason': 'missing_or_invalid_declared_source'}
    return verify_sources(task, sources, java, javac, timeout)


def apply_reference(task, omitted_path=None):
    sources = dict(task['sources'])
    for edit in task['reference_edits']:
        if edit['path'] == omitted_path:
            continue
        source = sources[edit['path']]
        if source.count(edit['old_text']) != 1:
            raise ValueError('参考修改必须恰好匹配一次：' + edit['path'])
        sources[edit['path']] = source.replace(edit['old_text'], edit['new_text'])
    return sources


def source_invariants(task, sources):
    """返回未修改文件的明确证据；内容哈希不包含凭据。"""
    return {'declaredSourcesExact': set(sources) == set(task['sources']),
            'unchangedCount': len(task['unchanged_paths']),
            'unchangedSources': {
                name: {'unchanged': sources.get(name) == task['sources'][name],
                       'sha256': hashlib.sha256(sources[name].encode('utf-8')).hexdigest() if name in sources else None}
                for name in task['unchanged_paths']}}


def verify_sources(task, sources, java, javac, timeout=20):
    """编译精确声明的源码快照和私有断言，不使用代理生成的 class 文件。

    这是验证依赖隔离，并非恶意代码安全沙箱。源码以当前账户权限执行；
    自带夹具和参考代码不进行外部输入输出。
    """
    evidence = source_invariants(task, sources)
    if not evidence['declaredSourcesExact'] or not evidence['unchangedCount'] or not all(
            item['unchanged'] for item in evidence['unchangedSources'].values()):
        return {'passed': False, 'phase': 'source-invariants', **evidence}
    phase = 'prepare'
    try:
        with tempfile.TemporaryDirectory(prefix='harness-repository-check-') as temporary:
            root = Path(temporary)
            classes, empty = root / 'classes', root / 'empty-path'
            classes.mkdir()
            empty.mkdir()
            java_sources = []
            for name in sorted(task['sources']):
                relative = Path(name)
                if relative.is_absolute() or '..' in relative.parts or relative.suffix != '.java':
                    raise ValueError('声明的源码路径无效')
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(sources[name].encode('utf-8'))
                java_sources.append(str(target))
            check = root / 'Check.java'
            check.write_bytes(task['checks'].encode('utf-8'))
            phase = 'compile'
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '--release', '21', '-proc:none',
                                       '-classpath', str(empty), '-sourcepath', str(empty), '-d', str(classes),
                                       *java_sources, str(check)], cwd=root, capture_output=True, timeout=timeout)
            if compiled.returncode:
                return {'passed': False, 'phase': phase, 'exitCode': compiled.returncode, **evidence}
            phase = 'assertions'
            checked = subprocess.run([java, '-cp', str(classes), 'Check'], cwd=root,
                                     capture_output=True, timeout=timeout)
            return {'passed': checked.returncode == 0 and b'CHECKS_PASS' in checked.stdout.splitlines(),
                    'phase': phase, 'exitCode': checked.returncode, **evidence}
    except subprocess.TimeoutExpired:
        return {'passed': False, 'phase': 'timeout', 'timedOutPhase': phase, **evidence}
    except OSError:
        return {'passed': False, 'phase': phase, 'reason': 'checker_io_failure', **evidence}


def task_metrics(task):
    return {'productionClasses': len(task['sources']),
            'productionNonblankLines': sum(sum(bool(line.strip()) for line in source.splitlines())
                                          for source in task['sources'].values()),
            'productionLines': sum(len(source.splitlines()) for source in task['sources'].values()),
            'expectedChangedPaths': task['expected_changed_paths'],
            'unchangedPaths': task['unchanged_paths'], 'referenceEdits': len(task['reference_edits'])}


def path_negative_control(task, repaired, variant, java, javac, timeout):
    """使用正确源码验证路径边界；平台可能明确不支持创建链接。"""
    with tempfile.TemporaryDirectory(prefix='harness-repository-path-control-') as temporary:
        root = Path(temporary)
        workspace = root / 'workspace'
        workspace.mkdir()
        prepare_workspace(task, workspace)
        for name, source in repaired.items():
            (workspace / name).write_bytes(source.encode('utf-8'))
        if variant == 'extra-runtime-java':
            runtime = workspace / '.harness'
            runtime.mkdir()
            (runtime / 'Extra.java').write_bytes(b'final class Extra {}\n')
            expected_reason = 'declared_source_set_mismatch'
        else:
            if variant == 'source-symlink':
                link = workspace / task['expected_changed_paths'][0]
                target = root / 'external-source.java'
                target.write_bytes(link.read_bytes())
                link.unlink()
                directory = False
            else:
                link = workspace / 'src'
                target = root / 'redirected-src'
                # 两个端点都已确认是此隔离临时根目录的子路径。
                if link.resolve().parent != workspace.resolve() or target.parent.resolve() != root.resolve():
                    raise ValueError('夹具重定向端点无效')
                link.rename(target)
                directory = True
            try:
                link.symlink_to(target, target_is_directory=directory)
            except (OSError, NotImplementedError) as error:
                unsupported = (isinstance(error, NotImplementedError)
                               or getattr(error, 'winerror', None) == 1314
                               or getattr(error, 'errno', None) in
                               (errno.EPERM, errno.EACCES, errno.ENOSYS, errno.EOPNOTSUPP))
                if not unsupported:
                    raise
                return {'variant': variant, 'expectedPass': False, 'passed': None,
                        'skipped': True, 'phase': 'setup', 'reason': 'symlink_creation_unsupported',
                        'errorType': type(error).__name__}
            expected_reason = 'redirected_source_path'
        result = verify_workspace(task, workspace, java, javac, timeout)
        return {'variant': variant, 'expectedPass': False, 'skipped': False,
                'expectedReason': expected_reason,
                'gateMatched': result.get('phase') == 'snapshot' and result.get('reason') == expected_reason,
                **result}


def run_controls(task, java, javac, timeout=20):
    edits = {edit['path'] for edit in task['reference_edits']}
    if edits != set(task['expected_changed_paths']) or len(edits) < 3:
        raise ValueError('预期至少三个不同文件发生修改')
    if edits.intersection(task['unchanged_paths']) or not set(task['unchanged_paths']).issubset(task['sources']):
        raise ValueError('未修改路径要求相互矛盾')
    repaired = apply_reference(task)
    changed = {name for name in task['sources'] if repaired[name] != task['sources'][name]}
    if changed != edits:
        raise ValueError('参考修复改变了意外文件')
    variants = [('buggy', dict(task['sources']), False), ('reference-repair', repaired, True)]
    variants += [('omitted-file:' + name, apply_reference(task, name), False) for name in sorted(edits)]
    for name in task['unchanged_paths']:
        modified = dict(repaired)
        modified[name] += '\n// forbidden unrelated source modification\n'
        variants.append(('modified-unchanged:' + name, modified, False))
    results = []
    for name, sources, expected in variants:
        with tempfile.TemporaryDirectory(prefix='harness-repository-control-') as temporary:
            workspace = Path(temporary)
            prepare_workspace(task, workspace)
            for path, source in sources.items():
                (workspace / path).write_bytes(source.encode('utf-8'))
            result = verify_workspace(task, workspace, java, javac, timeout)
        results.append({'variant': name, 'expectedPass': expected, **result})
    results += [path_negative_control(task, repaired, variant, java, javac, timeout)
                for variant in ('extra-runtime-java', 'source-symlink', 'parent-directory-redirection')]
    executed = [row for row in results if not row.get('skipped', False)]
    return {'task': task['id'], **task_metrics(task), 'controls': results,
            'executedControls': len(executed),
            'skippedControls': [row['variant'] for row in results if row.get('skipped', False)],
            'allControlVariantsExecuted': len(executed) == len(results),
            'passed': all(row['passed'] == row['expectedPass'] and row.get('gateMatched', True)
                          for row in executed)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--timeout', type=int, default=20)
    parser.add_argument('--task', choices=[task['id'] for task in TASKS])
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error('timeout 必须为正数')
    selected = [task for task in TASKS if args.task is None or task['id'] == args.task]
    results = [run_controls(task, args.java, args.javac, args.timeout) for task in selected]
    report = {'mode': 'independent-repository-fixture-controls', 'javaRelease': 21,
              'checkerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'fixtureSha256': hashlib.sha256(json.dumps(TASKS, sort_keys=True).encode()).hexdigest(),
              'allControlVariantsExecuted': all(result['allControlVariantsExecuted'] for result in results),
              'passed': all(result['passed'] for result in results), 'tasks': results}
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
