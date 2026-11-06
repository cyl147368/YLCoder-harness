"""只读展开 YLCoder 执行日志中的报文引用，不连接网络、不调用模型。"""
import argparse
import copy
import json
from pathlib import Path
import re
import sys


def value_at(body, pointer):
    """从首条报文的原字段获取正文；空路径代表整个文本或 SSE。"""
    if pointer == '':
        return body
    if not isinstance(pointer, str) or not pointer.startswith('/'):
        raise ValueError('无效的报文内容位置：' + str(pointer))
    for part in pointer[1:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        body = body[int(part)] if isinstance(body, list) else body[part]
    return body


def read_wire_events(text):
    dictionary = {}
    events = []
    sections = re.finditer(r'^## HTTP (请求|响应)报文 #(\d+)\n(.*?)(?=^## |\Z)', text, re.M | re.S)
    for section in sections:
        kind, number, details = section.groups()
        markers = ('报文内容（body）；首次内容直接展示，重复内容使用引用：',
                   '报文内容（body）、引用位置（references）、本次首次出现的内容（new_content）：')
        marker = next((value for value in markers if value in details), None)
        if marker is None:
            raise ValueError('报文未记录完整，缺少内容区')
        lines = details.split(marker, 1)[1].lstrip('\r\n').splitlines()
        block = []
        for line in lines:
            if not line.startswith('    '):
                break
            block.append(line[4:])
        envelope = json.loads('\n'.join(block))
        version = envelope.get('format_version', 1)
        if version not in (1, 2):
            raise ValueError('不支持的报文格式版本：' + str(version))
        if version == 2:
            additions = {identifier: value_at(envelope['body'], pointer)
                         for identifier, pointer in envelope['content_locations'].items()}
        else:
            additions = envelope['new_content']
        for identifier, value in additions.items():
            if identifier in dictionary and dictionary[identifier] != value:
                raise ValueError('同一引用出现冲突：' + identifier)
            dictionary[identifier] = value
        body = copy.deepcopy(envelope['body'])
        for pointer, identifier in envelope['references'].items():
            if identifier not in dictionary:
                raise ValueError('缺少首次记录：' + identifier)
            value = copy.deepcopy(dictionary[identifier])
            if pointer == '':
                body = value
                continue
            parts = [part.replace('~1', '/').replace('~0', '~') for part in pointer.lstrip('/').split('/')]
            parent = body
            for part in parts[:-1]:
                parent = parent[int(part)] if isinstance(parent, list) else parent[part]
            if isinstance(parent, list):
                parent[int(parts[-1])] = value
            else:
                parent[parts[-1]] = value
        events.append({'request': int(number), 'kind': 'request' if kind == '请求' else 'response',
                       'truncated': '报文已截断' in details, 'body': body})
    return events


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path, help='执行日志 Markdown 文件')
    parser.add_argument('--request', type=int, help='只展示指定模型请求编号')
    parser.add_argument('--kind', choices=('request', 'response'), help='只展示请求或响应')
    options = parser.parse_args()
    try:
        text = options.file.read_text(encoding='utf-8')
        events = read_wire_events(text)
    except (OSError, ValueError, KeyError, IndexError, TypeError) as failure:
        parser.exit(1, '无法读取完整报文日志：' + str(failure) + '\n')
    selected = [event for event in events if (options.request is None or options.request == event['request'])
                and (options.kind is None or options.kind == event['kind'])]
    if not selected:
        if options.kind == 'response' and '## HTTP 响应状态 #' in text:
            parser.exit(1, '此日志已将返回内容整理在“模型回复 / 未完成回复片段 / 模型请求失败”中，不保存原始响应 JSON/SSE；请直接查看日志。\n')
        parser.exit(1, '没有找到对应的 HTTP 报文；旧版日志只有逻辑消息，不能还原实际请求。\n')
    for event in selected:
        name = '请求' if event['kind'] == 'request' else '响应'
        print('\n模型' + name + ' #' + str(event['request']) + ('（已截断）' if event['truncated'] else ''))
        body = event['body']
        print(body if isinstance(body, str) else json.dumps(body, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
