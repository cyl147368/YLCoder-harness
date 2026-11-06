"""校验新旧执行日志的报文还原；仅使用内存夹具。"""
import json
import io
import tempfile
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch
import unittest

from read_model_trace import main, read_wire_events


def section(number, envelope, legacy=False):
    marker = ('报文内容（body）、引用位置（references）、本次首次出现的内容（new_content）：'
              if legacy else '报文内容（body）；首次内容直接展示，重复内容使用引用：')
    block = '\n'.join('    ' + line for line in json.dumps(envelope, ensure_ascii=False, indent=2).splitlines())
    return f'## HTTP 请求报文 #{number}\n\n{marker}\n\n{block}\n\n'


class ReadModelTraceTest(unittest.TestCase):
    def test_legacy_logs_remain_readable(self):
        first = {'body': {'content': '[引用 wire-1]'}, 'references': {'/content': 'wire-1'},
                 'new_content': {'wire-1': '原始系统提示词'}}
        second = {**first, 'new_content': {}}
        events = read_wire_events(section(1, first, True) + section(2, second, True))
        self.assertEqual([event['body'] for event in events], [{'content': '原始系统提示词'}] * 2)

    def test_first_inline_and_later_references_restore_exact_fields(self):
        original = {'messages': [{'content': '系统提示词'}, {'content': '系统提示词'}],
                    'literal': '[引用 wire-1]', 'empty': '', 'missing': None, 'a/~': '特殊路径内容'}
        first = {'format_version': 2, 'body': {**original, 'messages': [original['messages'][0], {'content': '[引用 wire-1]'}]},
                 'content_locations': {'wire-1': '/messages/0/content', 'wire-2': '/a~1~0'},
                 'references': {'/messages/1/content': 'wire-1'}}
        second = {'format_version': 2, 'body': {'messages': [{'content': '[引用 wire-1]'}], 'a/~': '[引用 wire-2]'},
                  'content_locations': {}, 'references': {'/messages/0/content': 'wire-1', '/a~1~0': 'wire-2'}}
        events = read_wire_events(section(1, first) + section(2, second))
        self.assertEqual(events[0]['body'], original)
        self.assertEqual(events[1]['body'], {'messages': [{'content': '系统提示词'}], 'a/~': '特殊路径内容'})

    def test_sse_body_root_can_be_referenced(self):
        stream = 'data: {"delta":"你好"}\n\ndata: [DONE]\n\n'
        first = {'format_version': 2, 'body': stream, 'content_locations': {'wire-1': ''}, 'references': {}}
        second = {'format_version': 2, 'body': '[引用 wire-1]', 'content_locations': {}, 'references': {'': 'wire-1'}}
        events = read_wire_events(section(1, first) + section(2, second))
        self.assertEqual([event['body'] for event in events], [stream, stream])

    def test_missing_reference_is_reported_instead_of_returning_empty(self):
        envelope = {'format_version': 2, 'body': '[引用 wire-1]', 'content_locations': {}, 'references': {'': 'wire-1'}}
        with self.assertRaisesRegex(ValueError, '缺少首次记录'):
            read_wire_events(section(1, envelope))

    def test_missing_first_content_location_is_reported(self):
        envelope = {'format_version': 2, 'body': {}, 'content_locations': {'wire-1': '/missing'}, 'references': {}}
        with self.assertRaises(KeyError):
            read_wire_events(section(1, envelope))

    def test_processed_response_sections_do_not_affect_request_reconstruction(self):
        first = {'format_version': 2, 'body': {'content': '系统提示词'},
                 'content_locations': {'wire-1': '/content'}, 'references': {}}
        second = {'format_version': 2, 'body': {'content': '[引用 wire-1]'},
                  'content_locations': {}, 'references': {'/content': 'wire-1'}}
        text = section(1, first) + '## HTTP 响应状态 #1\n\nHTTP 200\n\n## 模型回复 #1\n\n你好\n\n' + section(2, second)
        events = read_wire_events(text)
        self.assertEqual([event['body'] for event in events], [{'content': '系统提示词'}] * 2)
        self.assertTrue(all(event['kind'] == 'request' for event in events))

    def test_old_raw_response_can_still_be_reconstructed(self):
        envelope = {'body': {'content': '[引用 wire-1]'}, 'references': {'/content': 'wire-1'},
                    'new_content': {'wire-1': '旧版回复'}}
        events = read_wire_events(section(1, envelope, True).replace('HTTP 请求报文', 'HTTP 响应报文'))
        self.assertEqual(events[0]['kind'], 'response')
        self.assertEqual(events[0]['body'], {'content': '旧版回复'})

    def test_response_filter_explains_processed_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'processed.md'
            file.write_text('## HTTP 响应状态 #1\n\nHTTP 200\n', encoding='utf-8')
            error = io.StringIO()
            with patch('sys.argv', ['read_model_trace.py', str(file), '--kind', 'response']), redirect_stderr(error):
                with self.assertRaises(SystemExit) as exit_result:
                    main()
            self.assertEqual(exit_result.exception.code, 1)
            self.assertIn('不保存原始响应 JSON/SSE', error.getvalue())
            self.assertNotIn('不能还原实际请求', error.getvalue())


if __name__ == '__main__':
    unittest.main()
