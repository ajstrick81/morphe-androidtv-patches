/* Standalone port of the design handoff's <bg-three> scene.
   Uses the global THREE (UMD build) instead of an ES-module import so it
   loads cleanly from file:// inside an Android WebView. Behavior matches
   the handoff spec: chevron slabs, light rings, dust, idle camera drift.
   Reads the `speed` attribute every frame (0 freezes motion). */
(function () {
  class BgThree extends HTMLElement {
    connectedCallback() {
      if (this._started || !window.THREE) return;
      this._started = true;
      const THREE = window.THREE;
      this.style.display = 'block';
      this.style.width = '100%';
      this.style.height = '100%';

      const renderer = new THREE.WebGLRenderer({ antialias: false, alpha: true, powerPreference: 'high-performance' });
      // TV is a fixed 1080p panel. Rendering at devicePixelRatio 2 means a
      // 3840x2160 buffer (4x the fragments) — the main cause of stutter on the
      // Onn GPU. Pin to 1x; at TV viewing distance it's indistinguishable.
      renderer.setPixelRatio(1);
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = 1.15;
      renderer.domElement.style.cssText = 'display:block;width:100%;height:100%';
      this.appendChild(renderer.domElement);

      const scene = new THREE.Scene();
      scene.fog = new THREE.FogExp2(0x070a0f, 0.055);
      const camera = new THREE.PerspectiveCamera(42, 16 / 9, 0.1, 120);
      camera.position.set(0, 0, 13);

      scene.add(new THREE.AmbientLight(0x4c5a6e, 0.5));
      const key = new THREE.DirectionalLight(0xdfe9f5, 1.5);
      key.position.set(-6, 8, 9);
      scene.add(key);
      const rimL = new THREE.PointLight(0xff1a32, 90, 40);
      rimL.position.set(-11, 1, 4);
      scene.add(rimL);
      const rimR = new THREE.PointLight(0xff1a32, 90, 40);
      rimR.position.set(11, -1, 4);
      scene.add(rimR);
      const fill = new THREE.PointLight(0x2a6cff, 26, 40);
      fill.position.set(2, -7, 6);
      scene.add(fill);

      const s = new THREE.Shape();
      s.moveTo(0, 0); s.lineTo(0.62, 0); s.lineTo(1.5, 3.0); s.lineTo(0.88, 3.0); s.closePath();
      const geo = new THREE.ExtrudeGeometry(s, { depth: 0.3, bevelEnabled: true, bevelSize: 0.05, bevelThickness: 0.05, bevelSegments: 2 });
      geo.center();

      const metal = new THREE.MeshStandardMaterial({ color: 0x161d26, metalness: 0.95, roughness: 0.28 });
      const glow = new THREE.MeshBasicMaterial({ color: 0xff2338, transparent: true, opacity: 0.9 });

      const slabs = [];
      const lanes = [
        [-12.5, 0.4, 1.6, 1.15], [-10.2, -3.4, -1.2, 0.85], [-14.5, 3.6, -3.4, 1.0],
        [-8.6, 4.6, -6.0, 0.62], [-9.4, -6.2, -4.4, 0.7],
        [12.4, -0.6, 1.4, 1.15], [10.4, 3.4, -1.0, 0.85], [14.6, -3.8, -3.2, 1.0],
        [8.8, -5.0, -6.2, 0.62], [9.6, 6.0, -4.6, 0.7],
        [-3.5, 7.4, -9.5, 0.8], [3.8, -7.6, -9.0, 0.8], [0.5, 8.6, -13.0, 0.6], [-1.5, -8.8, -12.0, 0.6]
      ];
      lanes.forEach((l, i) => {
        const g = new THREE.Group();
        const mesh = new THREE.Mesh(geo, metal);
        const halo = new THREE.Mesh(geo, glow);
        halo.scale.set(1.09, 1.02, 0.5);
        halo.position.z = -0.22;
        g.add(halo, mesh);
        g.position.set(l[0], l[1], l[2]);
        g.scale.setScalar(l[3]);
        g.rotation.z = 0.16;
        g.userData = { ph: i * 1.3, sp: 0.5 + (i % 4) * 0.16 };
        scene.add(g);
        slabs.push(g);
      });

      const ringA = new THREE.Mesh(
        new THREE.TorusGeometry(5.6, 0.055, 8, 96),
        new THREE.MeshBasicMaterial({ color: 0xff2338, transparent: true, opacity: 0.5 })
      );
      ringA.rotation.set(1.15, 0.3, 0);
      ringA.position.z = -6;
      scene.add(ringA);
      const ringB = new THREE.Mesh(
        new THREE.TorusGeometry(8.2, 0.04, 8, 96),
        new THREE.MeshBasicMaterial({ color: 0x8fa6c4, transparent: true, opacity: 0.22 })
      );
      ringB.rotation.set(1.32, -0.4, 0);
      ringB.position.z = -8;
      scene.add(ringB);

      const N = 240, pos = new Float32Array(N * 3);
      for (let i = 0; i < N; i++) {
        pos[i * 3] = (Math.random() - 0.5) * 34;
        pos[i * 3 + 1] = (Math.random() - 0.5) * 20;
        pos[i * 3 + 2] = -14 + Math.random() * 16;
      }
      const pg = new THREE.BufferGeometry();
      pg.setAttribute('position', new THREE.BufferAttribute(pos, 3));
      const dust = new THREE.Points(pg, new THREE.PointsMaterial({ color: 0xbfd2e8, size: 0.05, transparent: true, opacity: 0.5 }));
      scene.add(dust);

      const resize = () => {
        const w = this.clientWidth || 1920, h = this.clientHeight || 1080;
        renderer.setSize(w, h, false);
        camera.aspect = w / h;
        camera.updateProjectionMatrix();
      };
      resize();
      new ResizeObserver(resize).observe(this);

      // Cap rendering to ~30fps. The scene drifts slowly, so 30fps looks smooth,
      // and a steady 30 beats an erratic higher rate when the GPU is also busy
      // decoding the ad video underneath the overlay. Motion integrates the
      // accumulated time (step) so drift speed is unchanged by the throttle.
      const clock = new THREE.Clock();
      const MIN_FRAME = 1 / 30;
      let acc = 0;
      const tick = () => {
        this._raf = requestAnimationFrame(tick);
        const sp = parseFloat(this.getAttribute('speed') || '1') || 0;
        acc += Math.min(clock.getDelta(), 0.05);
        if (acc < MIN_FRAME) return;
        const step = acc; acc = 0;
        const t = clock.elapsedTime;
        slabs.forEach(g => {
          g.position.x += step * 0.85 * sp * g.userData.sp;
          if (g.position.x > 17) g.position.x = -17;
          g.position.y += Math.sin(t * 0.5 + g.userData.ph) * step * 0.22 * sp;
          g.rotation.y = Math.sin(t * 0.35 + g.userData.ph) * 0.34;
        });
        ringA.rotation.z += step * 0.16 * sp;
        ringB.rotation.z -= step * 0.1 * sp;
        dust.rotation.y = t * 0.012 * sp;
        camera.position.x = Math.sin(t * 0.13) * 0.5;
        camera.position.y = Math.cos(t * 0.1) * 0.32;
        camera.lookAt(0, 0, -2);
        renderer.render(scene, camera);
      };
      tick();
      this._dispose = () => { cancelAnimationFrame(this._raf); renderer.dispose(); };
    }
    disconnectedCallback() { this._dispose && this._dispose(); }
  }
  if (!customElements.get('bg-three')) customElements.define('bg-three', BgThree);
})();
