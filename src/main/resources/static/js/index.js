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

document.addEventListener('DOMContentLoaded', function() {
    const canvas = document.getElementById('grafic_pay');
    const map = canvas.getAttribute('data-map');
    const parsedMap = JSON.parse(map);
    
        const ctx = document.getElementById('grafic_pay').getContext('2d');
        const dates = Object.keys(parsedMap);
        const orders = Object.values(parsedMap);
    console.log(dates);
        console.log(orders);
    const myChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: dates,
            datasets: [{
                label: 'Оборот по дням',
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
})