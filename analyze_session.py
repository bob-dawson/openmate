import json, urllib.request

url = 'http://127.0.0.1:4096/session/ses_2212c30fcffeZ2ejum4Y19Mv4m/message?limit=80'
data = urllib.request.urlopen(url).read()
msgs = json.loads(data)
print(f'Total messages: {len(msgs)}')

for m in msgs:
    role = m['info']['role']
    mid = m['info']['id'][:25]
    question_parts = [p for p in m['parts'] if p.get('tool') == 'question']
    other_tool_parts = [p for p in m['parts'] if p.get('type') == 'tool' and p.get('tool') != 'question']
    text_parts = [p for p in m['parts'] if p.get('type') == 'text']

    if question_parts:
        for qp in question_parts:
            status = qp.get('state', {}).get('status')
            callID = qp.get('callID', '')[:30]
            input_data = qp.get('state', {}).get('input', {})
            input_preview = json.dumps(input_data, ensure_ascii=False)[:150]
            output = qp.get('state', {}).get('output', '')
            output_preview = (output[:100] if output else 'none')
            print(f'  {mid} {role} QUESTION status={status} callID={callID}')
            print(f'    input: {input_preview}')
            print(f'    output: {output_preview}')
    elif other_tool_parts:
        for tp in other_tool_parts:
            tname = tp.get('tool', '')
            tstatus = tp.get('state', {}).get('status', '')
            print(f'  {mid} {role} tool: {tname}({tstatus})')
    elif text_parts:
        for tp in text_parts:
            preview = tp.get('text', '')[:60].replace('\n', ' ')
            print(f'  {mid} {role} text: {preview}')
    else:
        print(f'  {mid} {role} (no displayable parts)')