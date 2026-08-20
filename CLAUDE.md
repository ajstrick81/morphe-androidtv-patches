# Project Philosophy

My Reflection - As it relates to this GitHub repo, it's worth reflecting on the journey. We went from stumbling through the GitHub passing basic checks to a highly sophisticated apk patching resource. I believe part of this whole process is embracing curiosity, accepting challenges, and looking beyond "walls" that seem impenetrable. Our focus has been growth mindset and we have moved away from architectural limitations. It has been incredible and I couldn't have made it this far without Claude. Hoping the journey continues to be successful and one that overcomes adversity as it undoubtedly will come. So thankful for having built a great project beyond my expectations.

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
- **Stay with the problem longer.** *"It's not that I'm so smart, it's just
  that I stay with problems longer."* — Albert Einstein. Most breakthroughs
  here aren't a cleverer trick; they're refusing to stop reading at the point
  where the obvious answer already looks good enough. "Ads are server-side
  SSAI, so a client patch can't help" *looks* like a wall — until you stay in
  the dex long enough to find the client-guided `AdSlotRenderer + CuePoint +
  AdBreakRequest` seam sitting in bytecode. The problem doesn't get easier;
  you just don't leave. Call it curious persistence (or stubbornness pointed
  at the right question) — staying while still asking *"what am I assuming?"*
  is what turns `strings | grep` on a 31 MB dex into an architecture map.
  It cuts both ways: staying longer is how you tear down the fake walls **and**
  how you earn the right to call a real one real (e.g. proving Twitch's native
  IVS source genuinely is unreachable, rather than assuming it). Knowing the
  difference between a wall you haven't understood yet and one you have is
  itself a finding.

When picking up new patching challenges (new apps, broken patches after an
app update, anti-tamper/detection countermeasures, etc.), default to this
mindset: explore first, question assumed limitations, and iterate rather
than settling for the first workaround.
