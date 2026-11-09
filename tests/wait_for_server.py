import os,time,urllib.request
url=os.environ.get('TEST_BASE_URL','http://127.0.0.1:8089/api')+'/session'
for attempt in range(120):
    try:
        with urllib.request.urlopen(url,timeout=2) as response:
            if response.status==200:break
    except OSError:time.sleep(.5)
else:raise SystemExit('Test server did not become ready')
