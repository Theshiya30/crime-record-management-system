// Enhanced features for Crime Record Management System

// Global variables for filtering
let allCrimes = [];

// Function to filter the crime table based on search and date filters
function filterTable() {
    const searchInput = document.getElementById('searchInput').value.toLowerCase();
    const filterCriteria = document.getElementById('filterCriteria').value;
    const startDate = document.getElementById('startDate').value;
    const endDate = document.getElementById('endDate').value;

    const tbody = document.getElementById('crimeTableBody');
    const rows = tbody.getElementsByTagName('tr');

    for (let i = 0; i < rows.length; i++) {
        const row = rows[i];
        const cells = row.getElementsByTagName('td');

        if (cells.length < 8) continue; // Skip if not enough cells

        const caseNumber = cells[0].textContent.toLowerCase();
        const crimeType = cells[1].textContent.toLowerCase();
        const location = cells[2].textContent.toLowerCase();
        const status = cells[3].textContent.toLowerCase();
        const reportedBy = cells[4].textContent.toLowerCase();
        const date = cells[5].textContent;
        const officer = cells[6].textContent.toLowerCase();

        let showRow = true;

        // Search filter
        if (searchInput) {
            let searchText = '';
            switch (filterCriteria) {
                case 'caseNumber':
                    searchText = caseNumber;
                    break;
                case 'crimeType':
                    searchText = crimeType;
                    break;
                case 'location':
                    searchText = location;
                    break;
                case 'reportedBy':
                    searchText = reportedBy;
                    break;
                case 'officerAssigned':
                    searchText = officer;
                    break;
                default: // 'all'
                    searchText = `${caseNumber} ${crimeType} ${location} ${status} ${reportedBy} ${officer}`;
            }
            if (!searchText.includes(searchInput)) {
                showRow = false;
            }
        }

        // Date filter
        if (showRow && (startDate || endDate)) {
            const rowDate = new Date(date);
            if (startDate && rowDate < new Date(startDate)) {
                showRow = false;
            }
            if (endDate && rowDate > new Date(endDate + 'T23:59:59')) {
                showRow = false;
            }
        }

        row.style.display = showRow ? '' : 'none';
    }
}

// Function to initialize the chart
function initializeChart() {
    const ctx = document.getElementById('crimeChart').getContext('2d');
    const chart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Theft', 'Assault', 'Robbery', 'Burglary', 'Fraud', 'Vandalism', 'Drug Offense', 'Other'],
            datasets: [{
                label: 'Number of Cases',
                data: [0, 0, 0, 0, 0, 0, 0, 0],
                backgroundColor: [
                    'rgba(255, 99, 132, 0.6)',
                    'rgba(54, 162, 235, 0.6)',
                    'rgba(255, 206, 86, 0.6)',
                    'rgba(75, 192, 192, 0.6)',
                    'rgba(153, 102, 255, 0.6)',
                    'rgba(255, 159, 64, 0.6)',
                    'rgba(199, 199, 199, 0.6)',
                    'rgba(83, 102, 255, 0.6)'
                ],
                borderColor: [
                    'rgba(255, 99, 132, 1)',
                    'rgba(54, 162, 235, 1)',
                    'rgba(255, 206, 86, 1)',
                    'rgba(75, 192, 192, 1)',
                    'rgba(153, 102, 255, 1)',
                    'rgba(255, 159, 64, 1)',
                    'rgba(199, 199, 199, 1)',
                    'rgba(83, 102, 255, 1)'
                ],
                borderWidth: 1
            }]
        },
        options: {
            responsive: true,
            scales: {
                y: {
                    beginAtZero: true
                }
            },
            plugins: {
                title: {
                    display: true,
                    text: 'Crime Types Distribution'
                }
            }
        }
    });

    return chart;
}

// Function to update the chart with crime data
function updateChart(crimes) {
    if (!window.crimeChart) {
        window.crimeChart = initializeChart();
    }

    const crimeCounts = {
        'Theft': 0,
        'Assault': 0,
        'Robbery': 0,
        'Burglary': 0,
        'Fraud': 0,
        'Vandalism': 0,
        'Drug Offense': 0,
        'Other': 0
    };

    crimes.forEach(crime => {
        const type = crime.crimeType;
        if (crimeCounts.hasOwnProperty(type)) {
            crimeCounts[type]++;
        }
    });

    window.crimeChart.data.datasets[0].data = Object.values(crimeCounts);
    window.crimeChart.update();
}

// Override the original displayCrimes function to include chart updates
const originalDisplayCrimes = displayCrimes;
displayCrimes = function(data) {
    originalDisplayCrimes(data);

    // Parse crimes data for chart
    if (data) {
        const crimes = data.split(';').filter(c => c.trim()).map(crime => {
            const [id, caseNumber, crimeType, description, location, status, reportedBy, reportedDate, officerAssigned] = crime.split('~');
            return { id, caseNumber, crimeType, description, location, status, reportedBy, reportedDate, officerAssigned };
        });
        updateChart(crimes);
        allCrimes = crimes; // Store for filtering
    }
};

// Initialize chart when page loads
document.addEventListener('DOMContentLoaded', function() {
    window.crimeChart = initializeChart();
});

// Export functions
window.filterTable = filterTable;
window.initializeChart = initializeChart;
window.updateChart = updateChart;
