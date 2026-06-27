<script>
    // ================================================================
    // 1. BACKGROUND CANVAS (stars + aircraft)
    // ================================================================
    (function() {
        const canvas = document.getElementById('bg-canvas');
        const ctx = canvas.getContext('2d');
        let w, h, t = 0,
            aid;
        let stars = [];

        function resize() {
            w = window.innerWidth;
            h = window.innerHeight;
            canvas.width = w;
            canvas.height = h;
            stars = Array.from({ length: 200 }, () => ({ x: Math.random() * w, y: Math.random() * h, size: Math.random() *
                    2 + 0.5, speed: Math.random() * 0.5 + 0.1 }));
        }
        window.addEventListener('resize', resize);

        function draw() {
            ctx.fillStyle = '#020817';
            ctx.fillRect(0, 0, w, h);
            ctx.fillStyle = '#ffffff';
            for (const s of stars) {
                ctx.beginPath();
                ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
                ctx.fill();
                s.x -= s.speed;
                if (s.x < 0) { s.x = w;
                    s.y = Math.random() * h; }
            }
            const px = (t * 2) % (w + 200) - 100;
            const py = h * 0.3 + Math.sin(t * 0.02) * 50;
            ctx.save();
            ctx.translate(px, py);
            ctx.beginPath();
            ctx.moveTo(-20, 0);
            ctx.lineTo(-200, 0);
            ctx.strokeStyle = 'rgba(255,255,255,0.2)';
            ctx.lineWidth = 4;
            ctx.stroke();
            ctx.fillStyle = '#3b82f6';
            ctx.beginPath();
            ctx.moveTo(20, 0);
            ctx.lineTo(0, -10);
            ctx.lineTo(-30, -10);
            ctx.lineTo(-20, 0);
            ctx.lineTo(-30, 10);
            ctx.lineTo(0, 10);
            ctx.closePath();
            ctx.fill();
            ctx.fillStyle = '#f59e0b';
            ctx.beginPath();
            ctx.arc(5, -2, 3.5, 0, Math.PI * 2);
            ctx.fill();
            ctx.restore();
            t += 1;
            aid = requestAnimationFrame(draw);
        }
        resize();
        draw();
        window.addEventListener('beforeunload', () => { if (aid) cancelAnimationFrame(aid); });
    })();

    // ================================================================
    // 2. VIEW MANAGEMENT
    // ================================================================
    function showView(id) {
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        document.getElementById(id).classList.add('active');
    }

    // ================================================================
    // 3. AUTHENTICATION
    // ================================================================
    (function() {
        const sif = document.getElementById('signinForm'),
            suf = document.getElementById('signupForm');
        const sie = document.getElementById('signinError'),
            sue = document.getElementById('signupError');
        const dn = document.getElementById('displayName'),
            lo = document.getElementById('logoutBtn');

        document.querySelectorAll('.auth-tabs button').forEach(b => {
            b.addEventListener('click', function() {
                document.querySelectorAll('.auth-tabs button').forEach(x => x.classList.remove('active'));
                this.classList.add('active');
                const tab = this.dataset.tab;
                if (tab === 'signin') { sif.classList.remove('hidden');
                    suf.classList.add('hidden'); } else { sif.classList.add('hidden');
                    suf.classList.remove('hidden'); }
                sie.textContent = '';
                sue.textContent = '';
            });
        });

        function getUsers() { try { return JSON.parse(localStorage.getItem('mlab_users')) || []; } catch { return []; } }

        function saveUsers(u) { localStorage.setItem('mlab_users', JSON.stringify(u)); }

        function getSession() { try { return JSON.parse(sessionStorage.getItem('mlab_session')); } catch { return null; } }

        function setSession(u) { sessionStorage.setItem('mlab_session', JSON.stringify(u)); }

        function clearSession() { sessionStorage.removeItem('mlab_session'); }

        function checkSession() {
            const u = getSession();
            if (u) {
                dn.textContent = u.name || u.username || 'Pilot';
                showView('viewDashboard');
                if (typeof startSim === 'function') startSim();
                return true;
            }
            return false;
        }

        suf.addEventListener('submit', function(e) {
            e.preventDefault();
            const name = document.getElementById('signupName').value.trim();
            const email = document.getElementById('signupEmail').value.trim();
            const username = document.getElementById('signupUsername').value.trim();
            const pass = document.getElementById('signupPassword').value;
            const confirm = document.getElementById('signupConfirm').value;
            sue.textContent = '';
            if (!name || !email || !username || !pass || !confirm) { sue.textContent = 'All fields are required.';
                return; }
            if (pass.length < 6) { sue.textContent = 'Password must be at least 6 characters.';
                return; }
            if (pass !== confirm) { sue.textContent = 'Passwords do not match.';
                return; }
            const users = getUsers();
            if (users.find(u => u.email === email)) { sue.textContent = 'Email already registered.';
                return; }
            if (users.find(u => u.username === username)) { sue.textContent = 'Username already taken.';
                return; }
            const nu = { id: Date.now(), name, email, username, password: pass };
            users.push(nu);
            saveUsers(users);
            setSession({ id: nu.id, name: nu.name, username: nu.username });
            dn.textContent = nu.name;
            showView('viewDashboard');
            suf.reset();
            if (typeof startSim === 'function') startSim();
        });

        sif.addEventListener('submit', function(e) {
            e.preventDefault();
            const id = document.getElementById('signinEmail').value.trim();
            const pass = document.getElementById('signinPassword').value;
            sie.textContent = '';
            if (!id || !pass) { sie.textContent = 'Please fill in all fields.';
                return; }
            const users = getUsers();
            const u = users.find(x => (x.email === id || x.username === id) && x.password === pass);
            if (!u) { sie.textContent = 'Invalid credentials.';
                return; }
            setSession({ id: u.id, name: u.name, username: u.username });
            dn.textContent = u.name;
            showView('viewDashboard');
            sif.reset();
            if (typeof startSim === 'function') startSim();
        });

        lo.addEventListener('click', function() {
            clearSession();
            showView('viewLanding');
            document.querySelector('.auth-tabs button[data-tab="signin"]').click();
            if (typeof stopSim === 'function') stopSim();
        });

        if (!checkSession()) showView('viewLanding');
    })();

    // ================================================================
    // 4. NAVIGATION
    // ================================================================
    document.getElementById('landingBtn').addEventListener('click', () => showView('viewFeatures'));
    document.getElementById('featuresGetStarted').addEventListener('click', () => showView('viewAuth'));
    document.getElementById('featuresBack').addEventListener('click', () => showView('viewLanding'));
    document.getElementById('authBack').addEventListener('click', () => showView('viewFeatures'));

    // ================================================================
    // 5. FULL DYNAMIC SIMULATION ENGINE (PERFORMANCE FIXED)
    // ================================================================
    (function() {
        const canvas = document.getElementById('simCanvas');
        const ctx = canvas.getContext('2d');

        const sliders = {
            weight: document.getElementById('sliderWeight'),
            fuel: document.getElementById('sliderFuel'),
            altitude: document.getElementById('sliderAltitude'),
            thrust: document.getElementById('sliderThrust'),
            wingArea: document.getElementById('sliderWingArea'),
            aoa: document.getElementById('sliderAoA'),
            pitch: document.getElementById('sliderPitch'),
            passengers: document.getElementById('sliderPassengers'),
            passWeight: document.getElementById('sliderPassWeight'),
            station: document.getElementById('sliderStation'),
        };
        const disp = {
            weight: document.getElementById('dispWeight'),
            fuel: document.getElementById('dispFuel'),
            altitude: document.getElementById('dispAltitude'),
            thrust: document.getElementById('dispThrust'),
            wingArea: document.getElementById('dispWingArea'),
            aoa: document.getElementById('dispAoA'),
            pitch: document.getElementById('dispPitch'),
            passengers: document.getElementById('dispPassengers'),
            passWeight: document.getElementById('dispPassWeight'),
            station: document.getElementById('dispStation'),
        };
        const metrics = {
            lift: document.getElementById('metricLift'),
            drag: document.getElementById('metricDrag'),
            ld: document.getElementById('metricLD'),
            fuelFlow: document.getElementById('metricFuelFlow'),
            eta: document.getElementById('metricETA'),
            distance: document.getElementById('metricDistance'),
            airspeed: document.getElementById('metricAirspeed'),
            climb: document.getElementById('metricClimb'),
        };

        let state = {
            weight: 72000,
            fuel: 5000,
            altitude: 1000,
            thrust: 50000,
            wingArea: 50,
            aoa: 2,
            pitch: 0,
            passengers: 150,
            passWeight: 80,
            station: 500,
            airspeed: 120,
            distance: 0,
            time: 0,
            groundScroll: 0,
        };

        const G = 9.81,
            RHO_SEA = 1.225,
            SFC = 0.000015;

        function rho(alt) { return RHO_SEA * Math.exp(-alt / 7500); }

        function computeAero(st) {
            const r = rho(st.altitude);
            const V = st.airspeed;
            const S = st.wingArea;
            let CL = 0.095 * st.aoa;
            if (st.aoa > 16) {
                const stallFactor = 1 - (st.aoa - 16) / 10;
                CL = CL * Math.max(stallFactor, 0.2);
            }
            CL = Math.max(CL, -0.5);
            const CD0 = 0.025,
                e = 0.8,
                AR = S / 5;
            const CD_ind = (CL * CL) / (Math.PI * e * Math.max(AR, 1));
            const CD = CD0 + CD_ind;
            const L = 0.5 * r * V * V * S * CL;
            const D = 0.5 * r * V * V * S * CD;
            return { CL, CD, L, D };
        }

        // ---- AIRFLOW PARTICLES (FASTER) ----
        let airflowParticles = [];
        for (let i = 0; i < 150; i++) {
            airflowParticles.push({
                x: Math.random() * 1200 - 100,
                y: Math.random() * 600,
                size: 1 + Math.random() * 4,
                baseSpeed: 5 + Math.random() * 15,
                opacity: 0.1 + Math.random() * 0.3,
                length: 30 + Math.random() * 60,
            });
        }

        // Clouds
        let clouds = [];
        for (let i = 0; i < 10; i++) {
            clouds.push({
                x: Math.random() * 1200 - 200,
                y: 30 + Math.random() * 250,
                w: 60 + Math.random() * 150,
                h: 15 + Math.random() * 35,
                speed: 0.1 + Math.random() * 0.3,
                opacity: 0.15 + Math.random() * 0.35,
            });
        }

        // Buildings
        let buildings = [];
        for (let i = 0; i < 50; i++) {
            buildings.push({
                x: Math.random() * 1500 - 250,
                w: 8 + Math.random() * 35,
                h: 5 + Math.random() * 50,
                color: `hsl(${210 + Math.random() * 30}, 30%, ${25 + Math.random() * 35}%)`,
                windowColor: `rgba(255,255,200,${0.1 + Math.random() * 0.25})`,
            });
        }

        // Camera shake
        let shakeX = 0,
            shakeY = 0,
            shakeTime = 0;

        function updateSimulation(dt) {
            // Read sliders
            state.weight = parseFloat(sliders.weight.value);
            state.fuel = parseFloat(sliders.fuel.value);
            state.altitude = parseFloat(sliders.altitude.value);
            state.thrust = parseFloat(sliders.thrust.value);
            state.wingArea = parseFloat(sliders.wingArea.value);
            state.aoa = parseFloat(sliders.aoa.value);
            state.pitch = parseFloat(sliders.pitch.value);
            state.passengers = parseInt(sliders.passengers.value);
            state.passWeight = parseFloat(sliders.passWeight.value);
            state.station = parseFloat(sliders.station.value);

            const totalMass = state.weight + state.fuel + state.passengers * state.passWeight;
            const aero = computeAero(state);
            const T = state.thrust;
            const theta = state.pitch * Math.PI / 180;
            const alphaRad = state.aoa * Math.PI / 180;

            // Physics
            const accel = (T * Math.cos(alphaRad) - aero.D - totalMass * G * Math.sin(theta)) / totalMass;
            state.airspeed += accel * dt;
            if (state.airspeed < 0) state.airspeed = 0;
            if (state.airspeed > 450) state.airspeed = 450;

            const climbRate = state.airspeed * Math.sin(theta);
            state.altitude += climbRate * dt;
            if (state.altitude < 0) state.altitude = 0;
            if (state.altitude > 15000) state.altitude = 15000;

            const groundSpeed = state.airspeed * Math.cos(theta);
            state.distance += groundSpeed * dt / 1000;

            // --- FIX: Faster, smoother building scroll ---
            state.groundScroll -= groundSpeed * dt * 2.5;

            // Fuel
            const ff = SFC * state.thrust * Math.sqrt(rho(state.altitude) / RHO_SEA) * 3600;
            state.fuel -= ff * dt / 3600;
            if (state.fuel < 0) state.fuel = 0;

            // Camera shake
            shakeTime += dt;
            const turb = Math.max(0, 1 - state.altitude / 8000) * 3;
            const aoaShake = Math.abs(state.aoa) * 0.5;
            shakeX = Math.sin(shakeTime * 7.3) * (0.8 + turb * 0.3 + aoaShake * 0.5) +
                Math.sin(shakeTime * 11.7 + 1.2) * (0.5 + turb * 0.2);
            shakeY = Math.cos(shakeTime * 5.1 + 0.7) * (0.6 + turb * 0.25 + aoaShake * 0.3) +
                Math.cos(shakeTime * 9.3 + 2.1) * (0.4 + turb * 0.15);

            // Update displays
            disp.weight.textContent = Math.round(state.weight) + ' kg';
            disp.fuel.textContent = Math.round(state.fuel) + ' kg';
            disp.altitude.textContent = Math.round(state.altitude) + ' m';
            disp.thrust.textContent = Math.round(state.thrust) + ' N';
            disp.wingArea.textContent = state.wingArea + ' m²';
            disp.aoa.textContent = state.aoa.toFixed(1) + '°';
            disp.pitch.textContent = state.pitch.toFixed(1) + '°';
            disp.passengers.textContent = state.passengers;
            disp.passWeight.textContent = state.passWeight + ' kg';
            disp.station.textContent = state.station + ' km';

            metrics.lift.textContent = aero.L.toFixed(0) + ' N';
            metrics.drag.textContent = aero.D.toFixed(0) + ' N';
            metrics.ld.textContent = (aero.D > 0) ? (aero.L / aero.D).toFixed(2) : '0.00';
            metrics.fuelFlow.textContent = ff.toFixed(1) + ' kg/h';
            metrics.airspeed.textContent = Math.round(state.airspeed * 3.6) + ' km/h';
            metrics.climb.textContent = climbRate.toFixed(1) + ' m/s';

            const remaining = Math.max(0, state.station - state.distance);
            const speedKmph = state.airspeed * 3.6;
            if (speedKmph > 0) {
                const hours = remaining / speedKmph;
                const h = Math.floor(hours);
                const m = Math.floor((hours - h) * 60);
                metrics.eta.textContent = h + 'h ' + m + 'm';
            } else {
                metrics.eta.textContent = '--';
            }
            metrics.distance.textContent = state.distance.toFixed(1) + ' km';

            // --- FIX: Airflow particles speed boost ---
            const speedScale = 0.5 + (state.airspeed / 150);
            for (const p of airflowParticles) {
                p.x -= p.baseSpeed * speedScale * 2.0;
                if (p.x < -100) {
                    p.x = 1100 + Math.random() * 200;
                    p.y = Math.random() * 600;
                    p.baseSpeed = 5 + Math.random() * 15;
                    p.size = 1 + Math.random() * 4;
                    p.length = (30 + Math.random() * 60) * speedScale;
                }
            }

            // Move clouds
            for (const c of clouds) {
                c.x -= c.speed * (0.2 + state.airspeed / 800);
                if (c.x < -200) c.x = 1100 + Math.random() * 200;
            }
        }

        function renderSimulation() {
            const W = canvas.width,
                H = canvas.height;
            ctx.clearRect(0, 0, W, H);

            // ---- Sky ----
            const grad = ctx.createLinearGradient(0, 0, 0, H);
            const altFactor = Math.min(state.altitude / 10000, 1);
            grad.addColorStop(0, `rgb(${10 + 20 * altFactor}, ${15 + 30 * altFactor}, ${30 + 50 * altFactor})`);
            grad.addColorStop(0.5, `rgb(${26 + 30 * altFactor}, ${35 + 40 * altFactor}, ${64 + 60 * altFactor})`);
            grad.addColorStop(0.8, `rgb(${42 + 20 * altFactor}, ${63 + 20 * altFactor}, ${95 + 30 * altFactor})`);
            grad.addColorStop(1, `rgb(${74 + 10 * altFactor}, ${111 + 10 * altFactor}, ${143 + 10 * altFactor})`);
            ctx.fillStyle = grad;
            ctx.fillRect(0, 0, W, H);

            // ---- Clouds ----
            for (const c of clouds) {
                ctx.globalAlpha = c.opacity * (0.4 + 0.6 * (1 - altFactor));
                ctx.fillStyle = '#b0c4de';
                ctx.beginPath();
                ctx.ellipse(c.x, c.y, c.w / 2, c.h / 2, 0, 0, Math.PI * 2);
                ctx.fill();
                ctx.beginPath();
                ctx.ellipse(c.x - c.w * 0.3, c.y - c.h * 0.2, c.w * 0.4, c.h * 0.4, 0, 0, Math.PI * 2);
                ctx.fill();
                ctx.beginPath();
                ctx.ellipse(c.x + c.w * 0.3, c.y - c.h * 0.1, c.w * 0.35, c.h * 0.35, 0, 0, Math.PI * 2);
                ctx.fill();
            }
            ctx.globalAlpha = 1;

            // ---- Ground ----
            const groundY = H * 0.75;
            const aoaDrift = state.aoa * 1.5;
            ctx.fillStyle = '#1a2a3a';
            ctx.fillRect(0, groundY, W, H - groundY);
            ctx.fillStyle = '#2a4a3a';
            ctx.fillRect(0, groundY, W, 6);

            // ---- Runway ----
            ctx.strokeStyle = 'rgba(255,255,255,0.06)';
            ctx.lineWidth = 2;
            for (let i = 0; i < 14; i++) {
                const x = W * 0.08 + i * W * 0.065 + aoaDrift * 0.3;
                const x2 = x * 0.65 + W * 0.18 + aoaDrift * 0.2;
                ctx.beginPath();
                ctx.moveTo(x, groundY);
                ctx.lineTo(x2, H);
                ctx.stroke();
            }

            // ---- Buildings (Optimized: fewer windows, conditional rendering) ----
            const altScale = 1 - Math.min(state.altitude / 6000, 0.85);
            const drawWindows = state.altitude < 800;

            for (const b of buildings) {
                let bx = ((b.x + state.groundScroll) % (W + 400)) - 200 + aoaDrift * 0.4;
                if (bx < -60 || bx > W + 60) continue;
                const bh = b.h * (0.2 + 0.8 * altScale);
                const bw = b.w * (0.4 + 0.6 * altScale);
                ctx.fillStyle = b.color;
                ctx.fillRect(bx, groundY - bh, bw, bh);

                // Only draw windows at low altitude and if building is tall enough
                if (drawWindows && bh > 15) {
                    ctx.fillStyle = b.windowColor;
                    const winW = Math.max(2, bw * 0.15);
                    const winH = Math.max(3, bh * 0.08);
                    // Larger step size to reduce draw calls significantly
                    for (let wy = 0; wy < bh - 6; wy += winH * 5) {
                        for (let wx = 3; wx < bw - 5; wx += winW * 5) {
                            ctx.fillRect(bx + wx, groundY - bh + wy + 3, winW, winH);
                        }
                    }
                }
            }

            // ---- Airflow Particles (No shadowBlur for performance) ----
            for (const p of airflowParticles) {
                const alpha = p.opacity * (0.3 + 0.7 * (state.airspeed / 200));
                ctx.strokeStyle = `rgba(200,230,255,${alpha})`;
                ctx.lineWidth = p.size * 0.6;
                ctx.beginPath();
                ctx.moveTo(p.x, p.y);
                ctx.lineTo(p.x - p.length * (0.3 + state.airspeed / 400), p.y + (Math.random() - 0.5) * 2);
                ctx.stroke();
            }

            // ---- Aircraft ----
            const cx = W * 0.45 + shakeX * 0.8;
            const cy = H * 0.48 + shakeY * 0.6;
            const scale = Math.min(W, H) / 65;

            ctx.save();
            ctx.translate(cx, cy);
            const pitchRad = state.pitch * Math.PI / 180;
            ctx.rotate(pitchRad);

            // Fuselage
            ctx.fillStyle = '#3b82f6';
            ctx.shadowColor = 'rgba(59,130,246,0.3)';
            ctx.shadowBlur = 20;
            ctx.beginPath();
            ctx.moveTo(20 * scale, 0);
            ctx.lineTo(0, -10 * scale);
            ctx.lineTo(-30 * scale, -10 * scale);
            ctx.lineTo(-20 * scale, 0);
            ctx.lineTo(-30 * scale, 10 * scale);
            ctx.lineTo(0, 10 * scale);
            ctx.closePath();
            ctx.fill();

            // Cockpit
            ctx.shadowBlur = 30;
            ctx.fillStyle = '#f59e0b';
            ctx.beginPath();
            ctx.arc(5 * scale, -2 * scale, 3.5 * scale, 0, Math.PI * 2);
            ctx.fill();

            // Wings
            const wingSpan = Math.max(1, state.wingArea / 3.2);
            ctx.shadowBlur = 10;
            ctx.fillStyle = '#60a5fa';
            ctx.beginPath();
            ctx.moveTo(2 * scale, -2 * scale);
            ctx.lineTo(-wingSpan * scale, -8 * scale);
            ctx.lineTo(-wingSpan * scale, -6 * scale);
            ctx.lineTo(2 * scale, -1 * scale);
            ctx.closePath();
            ctx.fill();
            ctx.beginPath();
            ctx.moveTo(2 * scale, 2 * scale);
            ctx.lineTo(-wingSpan * scale, 8 * scale);
            ctx.lineTo(-wingSpan * scale, 6 * scale);
            ctx.lineTo(2 * scale, 1 * scale);
            ctx.closePath();
            ctx.fill();

            // Tail
            ctx.shadowBlur = 5;
            ctx.fillStyle = '#4b82f6';
            ctx.beginPath();
            ctx.moveTo(-20 * scale, -2 * scale);
            ctx.lineTo(-30 * scale, -6 * scale);
            ctx.lineTo(-32 * scale, -4 * scale);
            ctx.lineTo(-22 * scale, 0);
            ctx.closePath();
            ctx.fill();

            // Thrust flame
            ctx.shadowBlur = 0;
            if (state.thrust > 0) {
                const flameLen = 10 * scale + (state.thrust / 100000) * 35 * scale;
                const gradFlame = ctx.createRadialGradient(
                    -30 * scale, 0, 2,
                    -30 * scale - flameLen * 0.5, 0, flameLen
                );
                gradFlame.addColorStop(0, 'rgba(255,200,50,0.95)');
                gradFlame.addColorStop(0.3, 'rgba(255,150,30,0.8)');
                gradFlame.addColorStop(0.6, 'rgba(255,80,10,0.5)');
                gradFlame.addColorStop(1, 'rgba(255,50,0,0)');
                ctx.fillStyle = gradFlame;
                ctx.beginPath();
                ctx.ellipse(-30 * scale - flameLen * 0.4, 0, flameLen, 4 * scale, 0, 0, Math.PI * 2);
                ctx.fill();
            }

            // Contrails
            ctx.shadowBlur = 0;
            if (state.airspeed > 30) {
                ctx.strokeStyle = `rgba(255,255,255,${0.05 + 0.1 * (state.airspeed / 350)})`;
                ctx.lineWidth = 2 + state.airspeed / 100;
                ctx.beginPath();
                ctx.moveTo(-30 * scale, 0);
                ctx.lineTo(-100 * scale - state.airspeed * 0.2, 0);
                ctx.stroke();
            }
            ctx.shadowBlur = 0;
            ctx.restore();

            // ---- HUD ----
            ctx.fillStyle = 'rgba(0,255,100,0.7)';
            ctx.font = '13px monospace';
            const hudX = 12,
                hudY = 22;
            ctx.fillText(`ALT ${Math.round(state.altitude)}m`, hudX, hudY);
            ctx.fillText(`SPD ${Math.round(state.airspeed * 3.6)}km/h`, hudX, hudY + 20);
            ctx.fillText(`FUEL ${Math.round(state.fuel)}kg`, hudX, hudY + 40);
            ctx.fillText(`THR ${Math.round(state.thrust)}N`, hudX, hudY + 60);
            ctx.fillText(`AoA ${state.aoa.toFixed(1)}°`, hudX, hudY + 80);
            ctx.fillText(`PITCH ${state.pitch.toFixed(1)}°`, hudX, hudY + 100);

            // ---- Altimeter ----
            const altBarX = W - 25,
                altBarY = 25,
                altBarH = 180;
            ctx.fillStyle = 'rgba(255,255,255,0.15)';
            ctx.fillRect(altBarX, altBarY, 8, altBarH);
            const altPct = Math.min(state.altitude / 15000, 1);
            const gradAlt = ctx.createLinearGradient(0, altBarY + altBarH, 0, altBarY);
            gradAlt.addColorStop(0, '#3b82f6');
            gradAlt.addColorStop(0.5, '#60a5fa');
            gradAlt.addColorStop(1, '#93c5fd');
            ctx.fillStyle = gradAlt;
            ctx.fillRect(altBarX, altBarY + (1 - altPct) * altBarH, 8, altPct * altBarH);

            ctx.fillStyle = 'rgba(255,255,255,0.6)';
            ctx.font = '9px monospace';
            ctx.textAlign = 'center';
            ctx.fillText('ALT', altBarX + 4, altBarY - 4);
            ctx.fillText(Math.round(state.altitude / 1000) + 'k', altBarX + 4, altBarY + altBarH + 14);
            ctx.textAlign = 'left';

            // Cloud marker
            const cloudAltMark = 3000;
            const cloudY = altBarY + (1 - Math.min(cloudAltMark / 15000, 1)) * altBarH;
            ctx.fillStyle = 'rgba(255,255,255,0.4)';
            ctx.fillRect(altBarX - 4, cloudY - 1, 16, 2);
            ctx.fillStyle = 'rgba(255,255,255,0.3)';
            ctx.font = '10px monospace';
            ctx.fillText('☁', altBarX - 14, cloudY + 4);

            // AoA indicator
            const aoaX = W - 70,
                aoaY = H - 60;
            ctx.strokeStyle = 'rgba(255,255,255,0.2)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.arc(aoaX, aoaY, 30, -Math.PI * 0.8, Math.PI * 0.8);
            ctx.stroke();
            const aoaAngle = (state.aoa / 25) * Math.PI * 0.7;
            ctx.strokeStyle = state.aoa > 15 ? '#ef4444' : state.aoa > 10 ? '#f59e0b' : '#3b82f6';
            ctx.lineWidth = 3;
            ctx.beginPath();
            ctx.arc(aoaX, aoaY, 30, -aoaAngle, aoaAngle);
            ctx.stroke();
            ctx.fillStyle = 'rgba(255,255,255,0.4)';
            ctx.font = '8px monospace';
            ctx.textAlign = 'center';
            ctx.fillText('AoA', aoaX, aoaY + 45);
            ctx.fillText(state.aoa.toFixed(1) + '°', aoaX, aoaY + 56);
            ctx.textAlign = 'left';
        }

        // ---- Main loop ----
        let running = false,
            lastTime = 0,
            animId = null;

        function simLoop(timestamp) {
            if (!running) return;
            const dt = Math.min((timestamp - lastTime) / 1000, 0.05);
            lastTime = timestamp;
            updateSimulation(dt);
            renderSimulation();
            animId = requestAnimationFrame(simLoop);
        }

        function startSim() {
            if (running) return;
            running = true;
            lastTime = performance.now();
            state.weight = parseFloat(sliders.weight.value);
            state.fuel = parseFloat(sliders.fuel.value);
            state.altitude = parseFloat(sliders.altitude.value);
            state.thrust = parseFloat(sliders.thrust.value);
            state.wingArea = parseFloat(sliders.wingArea.value);
            state.aoa = parseFloat(sliders.aoa.value);
            state.pitch = parseFloat(sliders.pitch.value);
            state.passengers = parseInt(sliders.passengers.value);
            state.passWeight = parseFloat(sliders.passWeight.value);
            state.station = parseFloat(sliders.station.value);
            state.airspeed = 120;
            state.distance = 0;
            state.groundScroll = 0;
            if (animId) cancelAnimationFrame(animId);
            animId = requestAnimationFrame(simLoop);
        }

        function stopSim() {
            running = false;
            if (animId) { cancelAnimationFrame(animId);
                animId = null; }
        }

        window.startSim = startSim;
        window.stopSim = stopSim;

        if (document.getElementById('viewDashboard').classList.contains('active')) startSim();
        window.addEventListener('beforeunload', stopSim);

        // Handle canvas resize
        function resizeCanvas() {
            const rect = canvas.parentElement.getBoundingClientRect();
            const w = rect.width - 10;
            const h = w * 9 / 16;
            canvas.width = 1000;
            canvas.height = 562;
        }
        window.addEventListener('resize', resizeCanvas);
        setTimeout(resizeCanvas, 100);
    })();
</script>