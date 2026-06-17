import uiautomator2 as u2
import time

d = u2.connect('emulator-5554')

# Input path
fields = d(className='android.widget.EditText')
fields[0].set_text('/root/workspace')
time.sleep(0.3)

# Dismiss keyboard
if d(description='Done').exists:
    d(description='Done').click()
    time.sleep(0.5)

# Click Confirm
d(text='Confirm').click()
time.sleep(2)

import re
xml = d.dump_hierarchy()
d.screenshot(r'D:\openmate\simulator\screens\dir_selected.png')
texts = re.findall(r'text="([^"]+)"', xml)
print('Texts:', [t for t in texts if t and not re.match(r'^\d+:\d+', t)][:20])
