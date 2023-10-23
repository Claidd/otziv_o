const sideMenu = document.querySelector('aside');
const menuBtn = document.getElementById('menu-btn');
const closeBtn = document.getElementById('close-btn');

const darkMode = document.querySelector('.dark-mode');

menuBtn.addEventListener('click', () => {
    sideMenu.style.display = 'block';
});

closeBtn.addEventListener('click', () => {
    sideMenu.style.display = 'none';
});

darkMode.addEventListener('click', () => {
    document.body.classList.toggle('dark-mode-variables');
    darkMode.querySelector('span:nth-child(1)').classList.toggle('active');
    darkMode.querySelector('span:nth-child(2)').classList.toggle('active');
})



function changeActive(event) {
    // Удаляем класс .active у текущего активного элемента
    let currentActive = document.querySelector('.active');
    if (currentActive) {
        currentActive.classList.remove('active');
    }

    // Добавляем класс .active для элемента, на который кликнули
    let clickedElement = event.currentTarget;
    clickedElement.classList.add('active');
}

const progressBars = document.querySelectorAll('.progress-bar');
progressBars.forEach((progressBar) => {
    console.log(progressBar.getAttribute('data-percent'));
    const percentage = parseInt(progressBar.getAttribute('data-percent'));

    // Проверьте, что вы получили правильное значение процента
    console.log(percentage);

    // Убедитесь, что значение процента имеет правильный формат, прежде чем использовать его для strokeDashoffset
    if (!isNaN(percentage)) {
        const perimeter = 2 * Math.PI * 36;
        const dashOffset = perimeter - (percentage / 100) * perimeter;
        progressBar.style.strokeDashoffset = dashOffset;
    } else {
        console.error('Invalid percentage value');
    }
});



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