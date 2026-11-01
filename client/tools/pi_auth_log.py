import logging, os, time
import paramiko
logging.getLogger("paramiko").setLevel(logging.CRITICAL)
CLOUD=os.environ.get("SM_HOST","8.153.153.176"); PORT=12838; USER="dingji"
KEY=os.path.expanduser("~/.ssh/oc_relay_ed25519")
for i in range(24):
    c=paramiko.SSHClient(); c.load_system_host_keys(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        c.connect(hostname=CLOUD,port=PORT,username=USER,key_filename=KEY,look_for_keys=False,allow_agent=False,timeout=30); break
    except Exception as e:
        c.close(); time.sleep(2.5)
else:
    raise SystemExit("no tunnel")
def r(cmd):
    _,o,e=c.exec_command(cmd,timeout=60); o.channel.recv_exit_status()
    return (o.read().decode("utf-8","replace")+e.read().decode("utf-8","replace")).strip()
print("ACCEPTED LOG (last 15):\n"+r("sudo -n journalctl -u ssh --no-pager -n 300 2>/dev/null | grep -E 'Accepted|Failed password' | tail -15"))
print("\nPARKED DROP-IN:\n"+r("sudo -n ls -l /etc/ssh/sshd_config.d/"))
print("\nEFFECTIVE:\n"+r("sudo -n sshd -T 2>/dev/null | grep -iE '^(passwordauthentication|pubkeyauthentication|permitrootlogin|kbdinteractiveauthentication) ' | tr '\n' ' '"))
c.close()
