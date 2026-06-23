/**
 * Table of Contents - ScrollSpy and Back to Top
 * Hugo 文章目录滚动监听和返回顶部功能
 */

document.addEventListener('DOMContentLoaded', function() {
    initTOC();
    initBackToTop();
});

/**
 * 初始化 TOC ScrollSpy 功能
 */
function initTOC() {
    const tocNav = document.getElementById('TableOfContents');
    if (!tocNav) return;

    // 获取所有标题
    const headings = document.querySelectorAll('.post-content h1[id], .post-content h2[id], .post-content h3[id], .post-content h4[id]');
    if (headings.length === 0) return;

    // 获取所有目录链接
    const tocLinks = tocNav.querySelectorAll('a');
    if (tocLinks.length === 0) return;

    // 创建 IntersectionObserver
    const observerOptions = {
        root: null,
        rootMargin: '-80px 0px -60% 0px',
        threshold: 0
    };

    let activeId = '';

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                activeId = entry.target.getAttribute('id');
            }
        });
    }, observerOptions);

    // 观察所有标题
    headings.forEach(heading => {
        observer.observe(heading);
    });

    // 更新激活状态
    function updateActiveLink() {
        tocLinks.forEach(link => {
            link.classList.remove('active');
        });

        if (activeId) {
            const activeLink = tocNav.querySelector(`a[href="#${activeId}"]`);
            if (activeLink) {
                activeLink.classList.add('active');
                
                // 自动滚动目录到可见区域
                const tocContainer = document.querySelector('.toc-nav');
                if (tocContainer) {
                    const linkRect = activeLink.getBoundingClientRect();
                    const containerRect = tocContainer.getBoundingClientRect();
                    
                    if (linkRect.top < containerRect.top || linkRect.bottom > containerRect.bottom) {
                        activeLink.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    }
                }
            }
        }
    }

    // 监听滚动事件
    window.addEventListener('scroll', () => {
        requestAnimationFrame(updateActiveLink);
    }, { passive: true });

    // 初始更新
    updateActiveLink();
}

/**
 * 初始化返回顶部功能
 */
function initBackToTop() {
    const backToTopBtn = document.getElementById('back-to-top');
    if (!backToTopBtn) return;

    // 点击返回顶部
    backToTopBtn.addEventListener('click', function(e) {
        e.preventDefault();
        window.scrollTo({
            top: 0,
            behavior: 'smooth'
        });
    });

    // 监听滚动显示/隐藏
    let isVisible = false;
    window.addEventListener('scroll', () => {
        const shouldShow = window.scrollY > 400;
        
        if (shouldShow !== isVisible) {
            isVisible = shouldShow;
            backToTopBtn.style.opacity = isVisible ? '1' : '0.5';
        }
    }, { passive: true });
}
