# Project Philosophy

This project runs on a **growth mindset**. What started as basic patches that
just passed checks has grown into a sophisticated APK patching resource
because of a deliberate approach:

- **Stay curious.** Treat unfamiliar app internals, obfuscation, and manifest
  quirks as things to investigate, not blockers to route around.
- **Accept the hard problems.** Prefer tackling the real ad-injection or
  DRM/anti-tamper mechanism over a shallow workaround, even when it takes
  longer.
- **Walls are usually not walls.** When something looks architecturally
  impossible (a "can't be done" limitation), dig one level deeper before
  accepting that conclusion — most limitations turn out to be assumptions,
  not hard constraints.
- **Expect adversity and keep going.** Apps update, obfuscation changes,
  detection improves — setbacks are the normal cost of this work, not a
  signal to stop.

When picking up new patching challenges (new apps, broken patches after an
app update, anti-tamper/detection countermeasures, etc.), default to this
mindset: explore first, question assumed limitations, and iterate rather
than settling for the first workaround.
