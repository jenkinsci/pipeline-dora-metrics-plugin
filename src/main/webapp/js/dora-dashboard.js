// The period on screen: either the last `days` days or the calendar dates `from` to `to`.
var currentRange = { days: 30 };
// Bumped for every change of period, so a slow answer for an earlier choice is dropped.
var requestSeq = 0;
var viewerTz = (function() {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; }
})();

function toggleSection(header) {
    var body = header.nextElementSibling;
    var chevron = header.querySelector('.dora-chevron');
    var opening = body.classList.contains('collapsed');
    if (opening) {
        body.classList.remove('collapsed');
        body.style.maxHeight = body.scrollHeight + 'px';
    } else {
        body.classList.add('collapsed');
        body.style.maxHeight = '0';
    }
    if (chevron) chevron.classList.toggle('collapsed', !opening);
    header.setAttribute('aria-expanded', opening ? 'true' : 'false');
}

function highlightDateButton(btn) {
    document.querySelectorAll('.dora-date-btn').forEach(function(b) {
        b.classList.remove('jenkins-button--primary');
        b.classList.add('jenkins-button--tertiary');
    });
    if (btn) {
        btn.classList.remove('jenkins-button--tertiary');
        btn.classList.add('jenkins-button--primary');
    }
}

function setDays(days, btn) {
    currentRange = { days: days };
    highlightDateButton(btn);
    document.getElementById('dora-from').value = '';
    document.getElementById('dora-to').value = '';
    loadAll();
}

function applyCustomDate() {
    var from = document.getElementById('dora-from').value;
    var to = document.getElementById('dora-to').value;
    if (!from || !to) return;
    if (to < from) { var swap = from; from = to; to = swap; }
    currentRange = { from: from, to: to };
    highlightDateButton(null);
    loadAll();
}

function rangeQuery(range) {
    var q = range.from && range.to
        ? 'from=' + encodeURIComponent(range.from) + '&to=' + encodeURIComponent(range.to)
        : 'days=' + range.days;
    return viewerTz ? q + '&tz=' + encodeURIComponent(viewerTz) : q;
}

function getBaseUrl() {
    var root = document.querySelector('head').getAttribute('data-rooturl') || '';
    return root;
}

function topN() {
    var el = document.querySelector('.dora-dashboard');
    var n = el ? parseInt(el.getAttribute('data-top-n'), 10) : NaN;
    return isNaN(n) ? 10 : n;
}

function getJson(url) {
    return fetch(url, { credentials: 'same-origin' }).then(function(r) {
        if (!r.ok) { throw new Error('HTTP ' + r.status + ' for ' + url); }
        return r.json();
    });
}

// Everything on the page follows the chosen period: the cards, the charts, the sparklines,
// the rankings, the stage tables and the CSV link. On first load the server has already
// rendered the cards and tables for the default period, so only the charts are fetched.
function loadAll(initial) {
    var seq = ++requestSeq;
    var base = getBaseUrl();
    var q = rangeQuery(currentRange);
    var current = function() { return seq === requestSeq; };

    var csv = document.getElementById('dora-export-csv');
    if (csv) csv.href = base + '/dora-api/export?format=csv&' + q;

    if (!initial) getJson(base + '/dora-api/overview?' + q).then(function(data) {
        if (!current()) return;
        updateKpiCard('df', data.deployment_frequency);
        updateKpiCard('lt', data.lead_time);
        updateKpiCard('mttr', data.mttr);
        updateKpiCard('cfr', data.change_failure_rate);
        showRangeNote(data.period_days, data.retention_days);
    }).catch(function(e) { console.log('Overview load error:', e); });

    getJson(base + '/dora-api/trends?' + q).then(function(data) {
        if (!current()) return;
        var trends = data.trends || [];
        renderBuildChart(trends);
        renderDurationChart(trends);
        renderSparklines(trends);
    }).catch(function(e) { console.log('Chart load error:', e); });

    if (initial) return;

    getJson(base + '/dora-api/pipelines?' + q + '&limit=' + topN()).then(function(data) {
        if (!current()) return;
        renderPipelineRows('rank-slowest', data.slowest);
        renderPipelineRows('rank-failing', data.most_failing);
        renderPipelineRows('rank-improved', data.most_improved);
        renderPipelineRows('rank-flakiest', data.flakiest);
    }).catch(function(e) { console.log('Rankings load error:', e); });

    getJson(base + '/dora-api/stages?' + q + '&limit=' + topN()).then(function(data) {
        if (!current()) return;
        renderStageRows('stage-slowest', data.slowest);
        renderStageRows('stage-failing', data.most_failing);
    }).catch(function(e) { console.log('Stages load error:', e); });
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

function showRangeNote(periodDays, retentionDays) {
    var note = document.getElementById('dora-range-note');
    if (!note) return;
    note.textContent = periodDays && retentionDays && periodDays > retentionDays
        ? 'Only the last ' + retentionDays + ' days are kept (Data Retention), so earlier days show nothing.'
        : '';
}

// Each part of a job's full name is its own path part: a multibranch job such as
// "feature%2Fx" has to stay one part, so its "%" is encoded too.
function jobUrl(jobName) {
    return 'job/' + jobName.split('/').map(encodeURIComponent).join('/job/');
}

function cell(row, text) {
    var td = document.createElement('td');
    td.textContent = text;
    row.appendChild(td);
    return td;
}

function renderPipelineRows(tbodyId, rows) {
    var tbody = document.getElementById(tbodyId);
    if (!tbody) return;
    tbody.textContent = '';
    (rows || []).forEach(function(r, i) {
        var tr = document.createElement('tr');
        cell(tr, String(i + 1));
        var td = document.createElement('td');
        var a = document.createElement('a');
        a.className = 'jenkins-table__link';
        a.href = getBaseUrl() + '/' + jobUrl(r.job) + '/dora-metrics/';
        a.textContent = r.job;
        td.appendChild(a);
        tr.appendChild(td);
        cell(tr, r.value);
        cell(tr, String(r.build_count));
        tbody.appendChild(tr);
    });
}

function renderStageRows(tbodyId, rows) {
    var tbody = document.getElementById(tbodyId);
    if (!tbody) return;
    tbody.textContent = '';
    (rows || []).forEach(function(r, i) {
        var tr = document.createElement('tr');
        cell(tr, String(i + 1));
        cell(tr, r.stage);
        cell(tr, r.value);
        cell(tr, String(r.runs));
        tbody.appendChild(tr);
    });
}

function dayLabels(trends) {
    return trends.map(function(t) { return t.date.substring(5); });
}

function renderBuildChart(trends) {
    var ctx = document.getElementById('chart-builds');
    if (!ctx || typeof Chart === 'undefined') return;
    if (window._buildChart) window._buildChart.destroy();
    window._buildChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: dayLabels(trends),
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
    if (!ctx || typeof Chart === 'undefined') return;
    if (window._durChart) window._durChart.destroy();
    window._durChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: dayLabels(trends),
            datasets: [{
                label: 'Avg Duration (s)',
                // a day without builds has no duration, not a duration of zero
                data: trends.map(function(t) { return t.total_builds > 0 ? Math.round(t.avg_duration_ms / 1000) : null; }),
                borderColor: '#9a6700',
                backgroundColor: 'rgba(154,103,0,0.08)',
                fill: true, tension: 0.3, pointRadius: 2, borderWidth: 1.5, spanGaps: true
            }]
        },
        options: {
            responsive: true,
            plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 10 } } } },
            scales: { x: { grid: { display: false }, ticks: { font: { size: 9 }, maxRotation: 45 } }, y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { font: { size: 10 } } } }
        }
    });
}

// Each sparkline plots its own card's metric day by day. Days with nothing to measure
// are gaps rather than zeros.
function renderSparklines(trends) {
    var hours = function(ms) { return ms === null || ms === undefined ? null : ms / 3600000; };
    createSparkline('spark-df', trends.map(function(t) { return t.deployments; }), '#1a7f37');
    createSparkline('spark-lt', trends.map(function(t) { return hours(t.lead_time_ms); }), '#9a6700');
    createSparkline('spark-mttr', trends.map(function(t) { return hours(t.restore_time_ms); }), '#cf222e');
    createSparkline('spark-cfr', trends.map(function(t) { return t.change_failure_rate; }), '#cf222e');
}

function createSparkline(id, data, color) {
    var ctx = document.getElementById(id);
    if (!ctx || typeof Chart === 'undefined') return;
    if (ctx._chart) ctx._chart.destroy();
    ctx._chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: data.map(function() { return ''; }),
            datasets: [{ data: data, borderColor: color, backgroundColor: color + '15', fill: true, tension: 0.4, pointRadius: 0, borderWidth: 1.5, spanGaps: true }]
        },
        options: { responsive: false, plugins: { legend: { display: false }, tooltip: { enabled: false } }, scales: { x: { display: false }, y: { display: false } }, animation: false }
    });
}

// Init on page load - bind all event handlers
(function() {
    document.querySelectorAll('.dora-date-btn[data-days]').forEach(function(btn) {
        btn.addEventListener('click', function() { setDays(parseInt(this.getAttribute('data-days'), 10), this); });
    });

    var applyBtn = document.getElementById('dora-apply-date');
    if (applyBtn) applyBtn.addEventListener('click', applyCustomDate);

    // The section headers are buttons, so keyboard users get them for free
    document.querySelectorAll('.dora-toggle').forEach(function(header) {
        header.addEventListener('click', function() { toggleSection(this); });
    });

    // Show the default period in the date fields, in the viewer's own calendar
    var toLocalDate = function(d) {
        return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    };
    var today = new Date();
    var toEl = document.getElementById('dora-to');
    var fromEl = document.getElementById('dora-from');
    if (toEl) toEl.value = toLocalDate(today);
    if (fromEl) fromEl.value = toLocalDate(new Date(today.getTime() - 30 * 86400000));

    // Build history import. Only rendered for users who may run it, but the endpoint
    // checks the permission again, so hiding the button is convenience and not security.
    var importBtn = document.getElementById('dora-import-btn');
    if (importBtn) {
        importBtn.addEventListener('click', startImport);
        showRememberedImportSummary();
        // An import may already be running, started automatically or from another tab.
        // Only asked for when the button is there, since the endpoint needs Administer.
        checkImportOnLoad();
    }

    loadAll(true);
})();

// sessionStorage can be unavailable or throw, so never let it break the page.
function rememberImportSummary(text) {
    try { window.sessionStorage.setItem('doraImportSummary', text || ''); } catch (e) { /* ignore */ }
}

function showRememberedImportSummary() {
    var text = null;
    try {
        text = window.sessionStorage.getItem('doraImportSummary');
        if (text !== null) { window.sessionStorage.removeItem('doraImportSummary'); }
    } catch (e) { return; }
    if (text) { setImportStatus(text); }
}

function checkImportOnLoad() {
    var base = getBaseUrl();
    fetch(base + '/dora-api/importStatus')
        .then(function(r) { return r.ok ? r.json() : null; })
        .then(function(data) {
            if (!data || !data.running) { return; }
            setImportStatus(describeImport(data));
            pollImportStatus(base);
        })
        .catch(function() { /* nothing useful to show */ });
}

function setImportStatus(text) {
    var el = document.getElementById('dora-import-status');
    if (el) { el.textContent = text || ''; }
}

function describeImport(data) {
    if (!data) { return ''; }
    if (data.running) {
        return typeof data.recorded === 'undefined'
            ? 'Import running...'
            : 'Import running, ' + data.recorded + ' recorded so far...';
    }
    if (typeof data.recorded === 'undefined') { return ''; }
    if (data.error) { return 'Import failed: ' + data.error; }
    if (data.completed === false) { return 'Import stopped early, ' + data.recorded + ' recorded.'; }
    var text = 'Imported ' + data.recorded + ' build' + (data.recorded === 1 ? '' : 's')
        + ' from ' + data.jobs + ' job' + (data.jobs === 1 ? '' : 's');
    if (data.skipped) { text += ', ' + data.skipped + ' skipped'; }
    if (data.failed) { text += ', ' + data.failed + ' failed'; }
    return text + '.';
}

function pollImportStatus(base) {
    fetch(base + '/dora-api/importStatus')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            setImportStatus(describeImport(data));
            if (data.running) {
                window.setTimeout(function() { pollImportStatus(base); }, 2000);
            } else {
                // Reload so every part of the page, including the server-rendered first
                // view, shows the imported builds. Carry the counters across the reload so
                // they are still readable afterwards.
                rememberImportSummary(describeImport(data));
                window.location.reload();
            }
        })
        .catch(function() { setImportStatus('Could not read import status.'); });
}

function startImport() {
    var base = getBaseUrl();
    dialog.confirm('Import build history?', {
        message: 'This reads builds already on disk and adds any that are missing. '
            + 'Builds already recorded are left alone.'
    }).then(function() {
        var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        crumb.wrap(headers);
        setImportStatus('Starting...');
        fetch(base + '/dora-api/importHistory', { method: 'POST', headers: headers })
            .then(function(r) {
                if (r.status === 403) {
                    dialog.alert('Session expired', { message: 'Reload the page and try again.' });
                    setImportStatus('');
                    return null;
                }
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.json();
            })
            .then(function(data) {
                if (!data) { return; }
                setImportStatus(data.message || 'Import running...');
                pollImportStatus(base);
            })
            .catch(function() { setImportStatus('Could not start the import.'); });
    }, function() { /* cancelled */ });
}
