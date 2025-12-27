const sideMenu = document.querySelector('aside');
const menuBtn = document.getElementById('menu-btn');
const closeBtn = document.getElementById('close-btn');
// const darkMode = document.querySelector('.dark-mode');

menuBtn.addEventListener('click', () => {
    sideMenu.style.display = 'block';
});

closeBtn.addEventListener('click', () => {
    sideMenu.style.display = 'none';
});


function changeActive(event) {
    const sidebarLinks = document.querySelectorAll('.sidebar a');
    sidebarLinks.forEach(link => {
        link.classList.remove('active');
    });
    let clickedElement = event.currentTarget;
    clickedElement.classList.add('active');
}




document.addEventListener('DOMContentLoaded', function() {
    
    const darkMode = document.querySelector('.dark-mode');
    const isDarkMode = localStorage.getItem('isDarkMode');

    if (isDarkMode === 'true') {
        document.body.classList.add('dark-mode-variables');
        darkMode.querySelector('span:nth-child(1)').classList.remove('active');
        darkMode.querySelector('span:nth-child(2)').classList.add('active');
    } else {
        document.body.classList.remove('dark-mode-variables');
        darkMode.querySelector('span:nth-child(1)').classList.add('active');
        darkMode.querySelector('span:nth-child(2)').classList.remove('active');
    }

    darkMode.addEventListener('click', () => {
        const isCurrentlyDark = document.body.classList.contains('dark-mode-variables');
        if (isCurrentlyDark) {
            localStorage.setItem('isDarkMode', 'false');
            darkMode.querySelector('span:nth-child(1)').classList.add('active');
            darkMode.querySelector('span:nth-child(2)').classList.remove('active');
        } else {
            localStorage.setItem('isDarkMode', 'true');
            darkMode.querySelector('span:nth-child(1)').classList.remove('active');
            darkMode.querySelector('span:nth-child(2)').classList.add('active');
        }
        document.body.classList.toggle('dark-mode-variables');
    });

    const currentPath = window.location.pathname;
    const sidebarLinks = document.querySelectorAll('.sidebar a');

    sidebarLinks.forEach(link => {
        const linkPath = link.getAttribute('href');
        if (linkPath === currentPath) {
            link.classList.add('active');
        }
    });
});

function goBack(event) {
    event.preventDefault();
    window.history.back();
  }





const progressBars = document.querySelectorAll('.progress-bar');
progressBars.forEach((progressBar) => {
    console.log(progressBar.getAttribute('data-percent'));
    const percentage = parseInt(progressBar.getAttribute('data-percent'));

    // Проверьте, что вы получили правильное значение процента

    // Убедитесь, что значение процента имеет правильный формат, прежде чем использовать его для strokeDashoffset
    if (!isNaN(percentage)) {
        const perimeter = 2 * Math.PI * 36;
        const dashOffset = perimeter - (percentage / 100) * perimeter;
        progressBar.style.strokeDashoffset = dashOffset;
    } else {
        console.error('Invalid percentage value');
    }
});


document.addEventListener('DOMContentLoaded', function() {
const canvas = document.getElementById('grafic_zp');
const map = canvas.getAttribute('data-map');
const parsedMap = JSON.parse(map);

    const ctx = document.getElementById('grafic_zp').getContext('2d');
    const dates = Object.keys(parsedMap);
    const orders = Object.values(parsedMap);
console.log(dates);
    console.log(orders);
const myChart = new Chart(ctx, {
    type: 'line',
    data: {
        labels: dates,
        datasets: [{
            label: 'ЗП по дням',
            data: orders,
            backgroundColor: 'rgba(75, 192, 192, 0.2)',
            borderColor: 'rgba(75, 192, 192, 1)',
            borderWidth: 1
        }]
    },
    options: {
        scales: {
            y: {
                beginAtZero: true
            }
        }
    }
});

});

//  ========================== ГРАФИКИ ===========================

// График за месяц


document.addEventListener('DOMContentLoaded', function () {
    const canvas = document.getElementById('grafic_pay');
    const map = canvas.getAttribute('data-map');
    const parsedMap = JSON.parse(map);

    const ctx = canvas.getContext('2d');
    const dates = Object.keys(parsedMap);
    const orders = Object.values(parsedMap);

    const myChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: dates,
            datasets: [{
                label: 'Оборот по дням',
                data: orders,
                backgroundColor: 'rgba(75, 192, 192, 0.2)',
                borderColor: 'rgba(75, 192, 192, 1)',
                borderWidth: 1,
                pointRadius: 3,
                pointHoverRadius: 5,
            }]
        },
        options: {
            responsive: true,
            plugins: {
                tooltip: {
                    enabled: true,
                    position: 'nearest',
                    callbacks: {
                        label: function (tooltipItem) {
                            return `День ${tooltipItem.label}: ${tooltipItem.raw} ₽`;
                        }
                    }
                    
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    title: {
                        display: true,
                        text: 'Сумма, ₽'
                    }
                },
                x: {
                    title: {
                        display: true,
                        text: 'Дни месяца'
                    }
                }
            }
        }
    });
});






// document.addEventListener('DOMContentLoaded', function() {
//     const canvas = document.getElementById('grafic_pay');
//     const map = canvas.getAttribute('data-map');
//     const parsedMap = JSON.parse(map);
    
//         const ctx = document.getElementById('grafic_pay').getContext('2d');
//         const dates = Object.keys(parsedMap);
//         const orders = Object.values(parsedMap);
//     console.log(dates);
//         console.log(orders);
//     const myChart = new Chart(ctx, {
//         type: 'line',
//         data: {
//             labels: dates,
//             datasets: [{
//                 label: 'Оборот по дням',
//                 data: orders,
//                 backgroundColor: 'rgba(75, 192, 192, 0.2)',
//                 borderColor: 'rgba(75, 192, 192, 1)',
//                 borderWidth: 1
//             }]
//         },
//         options: {
//             scales: {
//                 y: {
//                     beginAtZero: true
//                 }
//             }
//         }
//     });
    
//     });

    // График по месяцам в году
    document.addEventListener('DOMContentLoaded', function() {
        const monthlyCanvas = document.getElementById('grafic_pay_month');
        const map = monthlyCanvas.getAttribute('data-map');
        const parsedMap = JSON.parse(map);
    
        const ctx = monthlyCanvas.getContext('2d');
        const months = Array.from({ length: 12 }, (_, i) => i + 1);
        
        const datasets = Object.keys(parsedMap).map((year, index) => {
            const monthlyData = parsedMap[year];
            const data = months.map(month => monthlyData[month] || 0);
            const colors = ['#ea3362', '#4a9a86', '#FFCE56', '#4BC0C0', '#9966FF', '#FF9F40'];
    
            return {
                label: `Год: ${year}`,
                data: data,
                backgroundColor: colors[index % colors.length],
                borderColor: colors[index % colors.length],
                fill: false,
                borderWidth: 1
            };
        });
    
        new Chart(ctx, {
            type: 'line',
            data: {
                labels: months,
                datasets: datasets
            },
            options: {
                scales: {
                    y: {
                        beginAtZero: true
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Месяца'
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: true,
                        position: 'top',
                        labels: {
                            usePointStyle: true
                        }
                    }
                }
            }
        });
    });


    // График ЗП по месяцам
    document.addEventListener('DOMContentLoaded', function() {
        const monthlyCanvas = document.getElementById('grafic_zp_month');
        const map = monthlyCanvas.getAttribute('data-map');
        const parsedMap = JSON.parse(map);
    
        const ctx = monthlyCanvas.getContext('2d');
        const months = Array.from({ length: 12 }, (_, i) => i + 1);
        
        const datasets = Object.keys(parsedMap).map((year, index) => {
            const monthlyData = parsedMap[year];
            const data = months.map(month => monthlyData[month] || 0);
            const colors = ['#ea3362', '#4a9a86', '#FFCE56', '#4BC0C0', '#9966FF', '#FF9F40'];
    
            return {
                label: `Год: ${year}`,
                data: data,
                backgroundColor: colors[index % colors.length],
                borderColor: colors[index % colors.length],
                fill: false,
                borderWidth: 1
            };
        });
    
        new Chart(ctx, {
            type: 'line',
            data: {
                labels: months,
                datasets: datasets
            },
            options: {
                scales: {
                    y: {
                        beginAtZero: true
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Месяца'
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: true,
                        position: 'top',
                        labels: {
                            usePointStyle: true
                        }
                    }
                }
            }
        });
    });



//  ========================== ГРАФИКИ КОНЕЦ ===========================






    const dataTodayElement = document.getElementById('dataToday');
    const formElement = document.getElementById('myForm');
    
    formElement.addEventListener('submit', function(event) {
        event.preventDefault();
        const selectedDate = new Date(dataTodayElement.value);
        const year = selectedDate.getFullYear();
        let month = selectedDate.getMonth() + 1;
        let day = selectedDate.getDate();
    
        if (month < 10) {
            month = `0${month}`;
        }
        if (day < 10) {
            day = `0${day}`;
        }
    
        const formattedDate = `${year}-${month}-${day}`;
        localStorage.setItem('selectedDate', formattedDate);
        formElement.setAttribute('action', window.location.href);
        formElement.submit();
    });
    
    window.addEventListener('load', function() {
        const storedDate = localStorage.getItem('selectedDate');
        const today = new Date();
        if (storedDate) {
            dataTodayElement.value = storedDate;
            localStorage.setItem('selectedDate', formatDate(today)); // Изменение в этой строке
        } else {
            const year = today.getFullYear();
            let month = today.getMonth() + 1;
            let day = today.getDate();
    
            if (month < 10) {
                month = `0${month}`;
            }
            if (day < 10) {
                day = `0${day}`;
            }
    
            const formattedDate = `${year}-${month}-${day}`;
            dataTodayElement.value = formattedDate;
        }
    });
    
    function formatDate(date) {
        const year = date.getFullYear();
        let month = date.getMonth() + 1;
        let day = date.getDate();
    
        if (month < 10) {
            month = `0${month}`;
        }
        if (day < 10) {
            day = `0${day}`;
        }
    
        return `${year}-${month}-${day}`;
    }
    
    
    
    

    
    
    
    

    
    
    
    
    

    
    
// const dates = ['1', '2', '3','4', '5', '6', '7', '8', '9', '10','11', '12', '13', '14', '15', '16', '17','18', '19', '20', '21', '22', '23', '24','25', '26', '27', '28', '29', '30', '31'];
// const orders = [3, 4, 2, 6, 12, 1, 4];

Orders.forEach(order =>{
    const tr = document.createElement('tr');
    const trContent = `
        <td>${order.productName}</td>
        <td>${order.productNumber}</td>
        <td>${order.paymentStatus}</td>
        <td class="${order.status === 'Declined' ? 'danger' : order.status === 'Pending' ? 'warning' : 'primary'}">${order.status}</td>
        <td class="primary">Details</td>
    `;
    tr.innerHTML = trContent;
    document.querySelector('table tbody').appendChild(tr);
});