import pathlib,base64
p=pathlib.Path(r"D:\Project\ms\ServerMaintance\deploy\cloud-monitor-dotnet\CloudMonitor.Panel\CloudMonitor.Panel\Services\PanelApiExtensions.cs")
p.write_bytes(base64.b64decode(open(r"D:\Project\ms\ServerMaintance\tools\panel_api_ext.b64","rb").read()))
print("ok",p)