"""关联文件评估器的离线验收边界对照；不调用模型。"""
import json
from pathlib import Path
import tempfile

from check_repository_fixtures import prepare_workspace
from evaluate_repository import allowed_workspace, valid_https_endpoint
from repository_fixtures import TASKS


def main():
    endpoints = {
        'https://codegoai.com/v1': True,
        'https://localhost:443/v1': True,
        'https://': False,
        'http://example.com/v1': False,
        'https://user@example.com/v1': False,
        'https://example.com:70000/v1': False,
        'https://example.com/v1?secret=x': False,
        'https://example.com/v1#fragment': False,
        'https://example.com/\n': False,
        'https://example.com\\redirect/v1': False,
    }
    for value, expected in endpoints.items():
        assert bool(valid_https_endpoint(value)) == expected, '地址边界检查不一致'
    task = TASKS[0]
    with tempfile.TemporaryDirectory(prefix='repository-runner-boundary-') as directory:
        root = Path(directory)
        prepare_workspace(task, root)
        assert allowed_workspace(root, task)
        state = root / '.harness/sessions'
        state.mkdir(parents=True)
        checkpoint = state / 'session.json'
        checkpoint.write_text('{}', encoding='utf-8')
        assert allowed_workspace(root, task), '状态文件必须被允许'
        unexpected = state / 'Extra.java'
        unexpected.write_text('class Extra {}', encoding='utf-8')
        assert not allowed_workspace(root, task), '必须拒绝隐藏 Java 源码'
        unexpected.unlink()
        unexpected = root / 'src/Extra.java'
        unexpected.write_text('class Extra {}', encoding='utf-8')
        assert not allowed_workspace(root, task), '必须拒绝额外生产源码'
    print(json.dumps({'passed': True, 'mode': 'offline-runner-boundary-controls',
                      'endpointCases': len(endpoints), 'workspaceCases': 4}))


if __name__ == '__main__':
    main()
