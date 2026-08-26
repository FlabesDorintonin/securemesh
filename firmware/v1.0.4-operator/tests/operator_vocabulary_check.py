#!/usr/bin/env python3
from pathlib import Path
from html.parser import HTMLParser
import sys

ROOT = Path(__file__).resolve().parents[1]
HTML = (ROOT / 'LabPanel' / 'index.html').read_text(encoding='utf-8')
JS = (ROOT / 'LabPanel' / 'app.js').read_text(encoding='utf-8')
INO = (ROOT / 'SecureMesh_v1_0_4_OPERATOR.ino').read_text(encoding='utf-8')

class VisibleText(HTMLParser):
    def __init__(self):
        super().__init__(); self.parts=[]; self.skip=0
    def handle_starttag(self, tag, attrs):
        if tag in ('script','style'): self.skip += 1
    def handle_endtag(self, tag):
        if tag in ('script','style') and self.skip: self.skip -= 1
    def handle_data(self, data):
        if not self.skip: self.parts.append(data)

p=VisibleText(); p.feed(HTML)
visible=' '.join(' '.join(p.parts).split()).lower()
errors=[]
# VANGUARD, Bluetooth, GPS and SOS are intentionally valid operator vocabulary.
forbidden_html=[
    'manifest','exact g2','primary','secondary','pdr','rtt','e2e','opcode','payload',
    'fragment','mtu','heap','crypto','крипто','телеметр','диагностика','field test',
    'txq','hop ack','soft-weak'
]
for term in forbidden_html:
    if term in visible: errors.append(f'visible HTML contains forbidden term: {term}')

start=INO.find('// -------------------- Product information architecture')
end=INO.find('void printHelp()', start)
ui=INO[start:end if end != -1 else len(INO)].lower()
for token in ['"диагностика"','"крипто','"tx очередь','"метрики линка','"ble proto','"e2e','"hop ack','"rtt','"pdr','"manifest','"primary','"g2']:
    if token in ui: errors.append(f'OLED/operator region contains forbidden literal: {token}')

menu=INO[INO.find('static const UiMenuItem UI_QUICK_ITEMS'):INO.find('uint8_t detectOledAddress')]
if 'UiFeatureState::Planned' in menu:
    errors.append('planned roadmap features are visible in operator OLED menus')

required=['Начать автоматическую проверку','Проверка исправности узлов','Сохранить отчёт','Протокол VANGUARD']
for phrase in required:
    if phrase not in HTML: errors.append(f'missing required operator phrase: {phrase}')

for phrase in ['Сначала проведи Field Test','Диагностика маршрутов','нет свежих пакетов','Доверенное','Доверять']:
    if phrase in JS: errors.append(f'user-facing JS text contains old jargon: {phrase}')


# 128x64 OLED at scale 1 fits at most 21 glyphs in centered text.
import re
for line_no, line in enumerate(INO.splitlines(), 1):
    if 'uiDrawCenteredText(' not in line:
        continue
    for literal in re.findall(r'\"([^\"]*)\"', line):
        if len(literal) > 21:
            errors.append(f'OLED centered literal is too wide at line {line_no}: {literal!r}')

if errors:
    for e in errors: print('FAIL:',e)
    sys.exit(1)
print('Operator vocabulary check: PASS')
