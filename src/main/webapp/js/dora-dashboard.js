var currentDays = 30;

function toggleSection(header) {
    var body = header.nextElementSibling;
    var chevron = header.querySelector('.dora-chevron');
    if (body.classList.contains('collapsed')) {
        body.classList.remove('collapsed');
        body.style.maxHeight = body.scrollHeight + 'px';
        if (chevron) chevron.classList.remove('collapsed');
    } else {
        body.classList.add('collapsed');
        body.style.maxHeight = '0';
        if (chevron) chevron.classList.add('collapsed');
    }
}

function setDays(days, btn) {
    currentDays = days;
    document.querySelectorAll('.dora-date-btn').forEach(function(b) {
        b.classList.remove('jenkins-button--primary');
        b.classList.add('jenkins-button--tertiary');
    });
    if (btn) {
        btn.classList.remove('jenkins-button--tertiary');
        btn.classList.add('jenkins-button--primary');
    }
    document.getElementById('dora-from').value = '';
    document.getElementById('dora-to').value = '';
    loadCharts(days);
}

function applyCustomDate() {
    var from = document.getElementById('dora-from').value;
    var to = document.getElementById('dora-to').value;
    if (!from || !to) return;
    var fromMs = new Date(from).getTime();
    var toMs = new Date(to).getTime() + 86400000;
    var days = Math.ceil((toMs - fromMs) / 86400000);
    document.querySelectorAll('.dora-date-btn').forEach(function(b) {
        b.classList.remove('jenkins-button--primary');
        b.classList.add('jenkins-button--tertiary');
    });
    currentDays = days;
    loadCharts(days);
}

function getBaseUrl() {
    var root = document.querySelector('head').getAttribute('data-rooturl') || '';
    return root;
}

// The four KPI cards are rendered server-side on page load, so changing the date
// range used to leave them showing the initial period while the trend charts below
// refreshed. Re-fetch them from the same overview endpoint the page was built from.
function loadOverview(days) {
    var base = getBaseUrl();
    fetch(base + '/dora-api/overview?days=' + days)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            updateKpiCard('df', data.deployment_frequency);
            updateKpiCard('lt', data.lead_time);
            updateKpiCard('mttr', data.mttr);
            updateKpiCard('cfr', data.change_failure_rate);
        })
        .catch(function(e) { console.log('Overview load error:', e); });
}

function updateKpiCard(slug, metric) {
    if (!metric) return;
    var value = document.getElementById('kpi-' + slug + '-value');
    if (value) value.textContent = metric.value;
    var band = document.getElementById('kpi-' + slug + '-band');
    if (band) {
        band.textContent = metric.band;
        band.style.backgroundColor = metric.color;
    }
}

function loadCharts(days) {
    var base = getBaseUrl();
    loadOverview(days);
    fetch(base + '/dora-api/trends?days=' + days)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.trends && data.trends.length > 0) {
                renderBuildChart(data.trends);
                renderDurationChart(data.trends);
                renderSparklines(data.trends);
            }
        })
        .catch(function(e) { console.log('Chart load error:', e); });
}

function renderBuildChart(trends) {
    var ctx = document.getElementById('chart-builds');
    if (!ctx) return;
    if (window._buildChart) window._buildChart.destroy();
    window._buildChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: trends.map(function(t) { return t.date.substring(5); }),
            datasets: [
                { label: 'Successful', data: trends.map(function(t) { return t.successful; }), backgroundColor: 'rgba(26,127,55,0.7)', borderRadius: 2, barPercentage: 0.7 },
                { label: 'Failed', data: trends.map(function(t) { return t.failed; }), backgroundColor: 'rgba(207,34,46,0.7)', borderRadius: 2, barPercentage: 0.7 }
            ]
        },
        options: {
            responsive: true,
            plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 10 } } } },
            scales: { x: { stacked: true, grid: { display: false }, ticks: { font: { size: 9 }, maxRotation: 45 } }, y: { stacked: true, beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { font: { size: 10 } } } }
        }
    });
}

function renderDurationChart(trends) {
    var ctx = document.getElementById('chart-duration');
    if (!ctx) return;
    if (window._durChart) window._durChart.destroy();
    window._durChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: trends.map(function(t) { return t.date.substring(5); }),
            datasets: [{
                label: 'Avg Duration (s)',
                data: trends.map(function(t) { return Math.round(t.avg_duration_ms / 1000); }),
                borderColor: '#bf8700',
                backgroundColor: 'rgba(191,135,0,0.08)',
                fill: true, tension: 0.3, pointRadius: 2, borderWidth: 1.5
            }]
        },
        options: {
            responsive: true,
            plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 10 } } } },
            scales: { x: { grid: { display: false }, ticks: { font: { size: 9 }, maxRotation: 45 } }, y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { font: { size: 10 } } } }
        }
    });
}

function renderSparklines(trends) {
    var total = trends.map(function(t) { return t.total_builds; });
    var failed = trends.map(function(t) { return t.failed; });
    var durations = trends.map(function(t) { return t.avg_duration_ms / 1000; });
    createSparkline('spark-df', total, '#1a7f37');
    createSparkline('spark-lt', durations, '#bf8700');
    createSparkline('spark-mttr', failed, '#cf222e');
    createSparkline('spark-cfr', failed.map(function(f,i) { return total[i] > 0 ? (f/total[i]*100) : 0; }), '#cf222e');
}

function createSparkline(id, data, color) {
    var ctx = document.getElementById(id);
    if (!ctx || data.length === 0) return;
    if (ctx._chart) ctx._chart.destroy();
    ctx._chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: data.map(function() { return ''; }),
            datasets: [{ data: data, borderColor: color, backgroundColor: color + '15', fill: true, tension: 0.4, pointRadius: 0, borderWidth: 1.5 }]
        },
        options: { responsive: false, plugins: { legend: { display: false }, tooltip: { enabled: false } }, scales: { x: { display: false }, y: { display: false } }, animation: false }
    });
}

// Init on page load - bind all event handlers
(function() {
    // Date picker buttons
    document.querySelectorAll('.dora-date-btn[data-days]').forEach(function(btn) {
        btn.addEventListener('click', function() { setDays(parseInt(this.getAttribute('data-days')), this); });
    });

    // Custom date apply button
    var applyBtn = document.getElementById('dora-apply-date');
    if (applyBtn) applyBtn.addEventListener('click', applyCustomDate);

    // Collapsible section headers
    document.querySelectorAll('.dora-toggle').forEach(function(header) {
        header.addEventListener('click', function() { toggleSection(this); });
    });

    // Set default date range
    var today = new Date();
    var thirtyAgo = new Date(today.getTime() - 30 * 86400000);
    var toEl = document.getElementById('dora-to');
    var fromEl = document.getElementById('dora-from');
    if (toEl) toEl.value = today.toISOString().split('T')[0];
    if (fromEl) fromEl.value = thirtyAgo.toISOString().split('T')[0];

    if (typeof Chart !== 'undefined') { loadCharts(30); }
})();
