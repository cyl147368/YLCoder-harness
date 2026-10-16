"""自定义厂商报告与工作流验证的离线合成对照，不访问网络。"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
from validate_custom_provider import redact, workflow_evidence, workspace_allowed


def main():
    initial = 'public class Answer { public static int value() { return 41; } }\n'
    final = initial.replace('41', '42')
    checksum = hashlib.sha256(final.encode()).hexdigest()

    def pair(identity, name, content):
        call = {'id': identity, 'name': name, 'argumentsJson': json.dumps({'path': 'Answer.java'})}
        return [{'role': 'ASSISTANT', 'toolCalls': [call]},
                {'role': 'TOOL', 'toolCalls': [], 'toolCallId': identity, 'toolName': name, 'content': content}]

    before = pair('before', 'read_file', '1: ' + initial)
    edited = pair('edit', 'edit_file', 'Edited "Answer.java": replaced 1 literal occurrence; sha256=' + checksum + '\n--- "a/Answer.java"\n')
    chinese_header = '已编辑 "Answer.java": 已替换 1 处字面匹配；sha256='
    chinese_edited = pair('edit', 'edit_file', chinese_header + checksum + '\n--- "a/Answer.java"\n')
    after = pair('after', 'read_file', '1: ' + final)
    with tempfile.TemporaryDirectory(prefix='custom-validation-controls-') as temporary:
        workspace = Path(temporary)
        (workspace / 'Answer.java').write_text(final, encoding='utf-8')
        (workspace / 'task.yaml').write_text('{}\n', encoding='utf-8')
        runtime = workspace / '.harness/sessions'
        runtime.mkdir(parents=True)
        (runtime / 'fixture.json').write_text('{}', encoding='utf-8')
        session = {'transcript': before + edited + after}
        assert workflow_evidence(session, workspace, final)
        assert workflow_evidence({'transcript': before + chinese_edited + after}, workspace, final)
        listing = pair('listing', 'list_files', 'Answer.java\ntask.yaml\n')
        listing[0]['toolCalls'][0]['argumentsJson'] = '{}'
        assert workflow_evidence({'transcript': listing + before + edited + after}, workspace, final)
        assert workflow_evidence({'transcript': listing + before + chinese_edited + after}, workspace, final)
        crlf_final = final.replace('\n', '\r\n')
        crlf_edit = pair('edit', 'edit_file', 'Edited "Answer.java": replaced 1 literal occurrence; sha256=' + hashlib.sha256(crlf_final.encode()).hexdigest() + '\n')
        assert workflow_evidence({'transcript': before + crlf_edit + after}, workspace, final, crlf_final.encode())
        chinese_crlf_edit = pair('edit', 'edit_file', chinese_header + hashlib.sha256(crlf_final.encode()).hexdigest() + '\n')
        assert workflow_evidence({'transcript': before + chinese_crlf_edit + after}, workspace, final, crlf_final.encode())
        assert workspace_allowed(workspace)
        rejected = []
        for label, transcript in [('wrong-order', before + after + edited),
                                  ('no-before-read', edited + after),
                                  ('no-after-read', before + edited),
                                  ('failed-edit', before + pair('edit', 'edit_file', 'Tool failed: stale hash') + after),
                                  ('unchanged-edit', before + pair('edit', 'edit_file', 'Unchanged "Answer.java": sha256=' + checksum + '\n') + after),
                                  ('wrong-after-value', before + edited + pair('after', 'read_file', '1: ' + initial)),
                                  ('failed-read', pair('before', 'read_file', 'Tool failed: unreadable') + edited + after),
                                  ('wrong-edit-hash', before + pair('edit', 'edit_file', 'Edited "Answer.java": replaced 1 literal occurrence; sha256=' + '0' * 64 + '\n') + after),
                                  ('write-file', before + pair('write', 'write_file', 'Wrote "Answer.java"') + edited + after),
                                  ('command', before + pair('shell', 'run_command', 'Exit code: 0') + edited + after),
                                  ('mcp', before + pair('external', 'mcp_fixture', 'done') + edited + after)]:
            assert not workflow_evidence({'transcript': transcript}, workspace, final), label
            rejected.append(label)
        localized_rejected = []
        for label, transcript in [
                ('chinese-wrong-order', before + after + chinese_edited),
                ('chinese-no-before-read', chinese_edited + after),
                ('chinese-no-after-read', before + chinese_edited),
                ('chinese-wrong-after-value', before + chinese_edited + pair('after', 'read_file', '1: ' + initial)),
                ('chinese-failed-edit', before + pair('edit', 'edit_file', '工具失败：过期哈希') + after),
                ('chinese-unchanged-edit', before + pair('edit', 'edit_file', '未修改 "Answer.java": sha256=' + checksum + '\n') + after),
                ('chinese-failed-read', pair('before', 'read_file', '工具失败：无法读取') + chinese_edited + after),
                ('chinese-write-file', before + pair('write', 'write_file', '已写入 "Answer.java"') + chinese_edited + after),
                ('chinese-command', before + pair('shell', 'run_command', '退出码：0') + chinese_edited + after),
                ('chinese-mcp', before + pair('external', 'mcp_fixture', '完成') + chinese_edited + after)]:
            assert not workflow_evidence({'transcript': transcript}, workspace, final), label
            localized_rejected.append(label)
        for label, header in [
                ('chinese-wrong-hash', chinese_header + '0' * 64 + '\n'),
                ('chinese-wrong-count', chinese_header.replace(' 1 ', ' 2 ') + checksum + '\n'),
                ('chinese-wrong-path', chinese_header.replace('Answer.java', 'Other.java') + checksum + '\n'),
                ('mixed-english-header', 'Edited "Answer.java": 已替换 1 处字面匹配；sha256=' + checksum + '\n'),
                ('mixed-chinese-header', '已编辑 "Answer.java": replaced 1 literal occurrence; sha256=' + checksum + '\n'),
                ('chinese-ascii-semicolon', chinese_header.replace('；', '; ') + checksum + '\n'),
                ('chinese-missing-newline', chinese_header + checksum),
                ('chinese-short-hash', chinese_header + checksum[:-1] + '\n'),
                ('chinese-uppercase-hash', chinese_header + checksum.upper() + '\n')]:
            assert not workflow_evidence({'transcript': before + pair('edit', 'edit_file', header) + after}, workspace, final), label
            localized_rejected.append(label)
        duplicate = copy.deepcopy(session)
        duplicate['transcript'].append(copy.deepcopy(after[-1]))
        assert not workflow_evidence(duplicate, workspace, final)
        unmatched = copy.deepcopy(session)
        unmatched['transcript'][3]['toolCallId'] = 'unknown'
        assert not workflow_evidence(unmatched, workspace, final)
        wrong_path = copy.deepcopy(session)
        wrong_path['transcript'][0]['toolCalls'][0]['argumentsJson'] = '{"path":"other.java"}'
        assert not workflow_evidence(wrong_path, workspace, final)
        (workspace / 'unallowed.txt').write_text('unexpected', encoding='utf-8')
        assert not workspace_allowed(workspace)
        (workspace / 'unallowed.txt').unlink()
        assert workspace_allowed(workspace)

    artificial = 'artificial-quote"backslash\\newline\n\u4e2d'
    report = {'models': ['prefix-' + artificial + '-suffix'], 'headers': {'Location': artificial},
              artificial: {'nested': [artificial, 12, None]}}
    safe = redact(report, artificial)
    encoded = json.dumps(safe, ensure_ascii=True)
    decoded = json.loads(encoded)
    assert decoded['models'] == ['prefix-[redacted]-suffix']
    assert decoded['headers']['Location'] == '[redacted]'
    assert decoded['[redacted]']['nested'] == ['[redacted]', 12, None]
    assert json.dumps(artificial, ensure_ascii=True)[1:-1] not in encoded
    print(json.dumps({'status': 'PASS', 'positive': '成功且有序的读/改/读，运行时文件符合允许清单',
                      'positiveLanguages': ['en', 'zh'], 'positiveWorkflowCases': 6,
                      'rejected': rejected + ['duplicate-result', 'unmatched-result', 'wrong-path', 'unallowed-file'] + localized_rejected,
                      'redaction': '含转义人工凭据的嵌套值与键'}))


if __name__ == '__main__':
    main()
