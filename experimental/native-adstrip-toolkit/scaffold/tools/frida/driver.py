import sys, threading, frida

# Resilient variant of ssl_driver.py: this gadget's script.load() ACK is
# unreliable (raises TransportError after a timeout even though the script IS
# running on-device and delivering messages). So run load() in a daemon thread
# and keep the main process alive regardless -- messages keep flowing via
# on_message. Do NOT let a flaky load-ACK tear down a working session.

def on_message(message, data):
    if message.get('type') == 'log':
        print(message.get('payload', ''), flush=True)
    elif message.get('type') == 'send':
        print('[send] ' + str(message.get('payload')), flush=True)
    elif message.get('type') == 'error':
        print('[error] ' + str(message.get('description')), flush=True)
        print(message.get('stack', ''), flush=True)
    else:
        print('[msg] ' + str(message), flush=True)

def main():
    dev = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
    session = dev.attach('Gadget')
    script_path = sys.argv[1] if len(sys.argv) > 1 else 'ssl-probe.js'
    with open(script_path, 'r', encoding='utf-8') as f:
        src = f.read()
    script = session.create_script(src, runtime='v8')
    script.on('message', on_message)

    def do_load():
        try:
            script.load()
            print('[driver] load() ACK received', flush=True)
        except Exception as e:
            print('[driver] load() raised (ignored, script still runs): ' + repr(e), flush=True)

    t = threading.Thread(target=do_load, daemon=True)
    t.start()
    print('[driver] load dispatched on daemon thread; blocking forever', flush=True)
    # keep refs alive so GC doesn't detach the session/script
    threading.Event().wait()

if __name__ == '__main__':
    main()
