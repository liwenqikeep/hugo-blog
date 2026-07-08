/**
 * 文章列表筛选与搜索功能
 */
(function() {
    'use strict';

    // 配置
    const CONFIG = {
        itemsPerPage: 20,
        debounceDelay: 300
    };

    // 状态
    let state = {
        searchQuery: '',
        selectedCategories: [],
        selectedYears: [],
        selectedTags: [],
        currentPage: 1
    };

    // DOM 元素
    let elements = {};

    // 初始化
    function init() {
        cacheElements();
        bindEvents();
        updateUI();
    }

    // 缓存 DOM 元素
    function cacheElements() {
        elements = {
            postsList: document.getElementById('posts-list'),
            postItems: document.querySelectorAll('.post-item'),
            searchInput: document.getElementById('search-input'),
            pagination: document.getElementById('pagination'),
            noResults: document.getElementById('no-results'),
            totalCount: document.getElementById('total-count'),
            filters: document.querySelector('.post-filters'),
            filtersToggle: document.querySelector('.filters-toggle'),
            filtersClose: document.querySelector('.filters-close'),
            filtersReset: document.querySelector('.filters-reset'),
            filterGroups: document.querySelectorAll('.filter-group'),
            btnReset: document.querySelector('.btn-reset')
        };
    }

    // 绑定事件
    function bindEvents() {
        // 搜索输入
        if (elements.searchInput) {
            elements.searchInput.addEventListener('input', debounce(handleSearch, CONFIG.debounceDelay));
            elements.searchInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    handleSearch();
                }
            });
        }

        // 筛选复选框
        document.querySelectorAll('input[name="category"], input[name="year"], input[name="tag"]').forEach(checkbox => {
            checkbox.addEventListener('change', handleFilterChange);
        });

        // 手风琴展开/收起
        elements.filterGroups.forEach(group => {
            const header = group.querySelector('.filter-header');
            if (header) {
                header.addEventListener('click', () => {
                    const isCollapsed = group.dataset.collapsed === 'true';
                    group.dataset.collapsed = !isCollapsed;
                });
            }
        });

        // 移动端筛选切换
        if (elements.filtersToggle) {
            elements.filtersToggle.addEventListener('click', () => {
                elements.filters.classList.add('active');
            });
        }

        if (elements.filtersClose) {
            elements.filtersClose.addEventListener('click', () => {
                elements.filters.classList.remove('active');
            });
        }

        // 重置按钮
        if (elements.filtersReset) {
            elements.filtersReset.addEventListener('click', resetFilters);
        }

        if (elements.btnReset) {
            elements.btnReset.addEventListener('click', resetFilters);
        }
    }

    // 处理搜索
    function handleSearch() {
        state.searchQuery = elements.searchInput.value.toLowerCase().trim();
        state.currentPage = 1;
        updateUI();
    }

    // 处理筛选变化
    function handleFilterChange() {
        // 更新状态
        state.selectedCategories = Array.from(document.querySelectorAll('input[name="category"]:checked')).map(cb => cb.value.toLowerCase());
        state.selectedYears = Array.from(document.querySelectorAll('input[name="year"]:checked')).map(cb => cb.value);
        state.selectedTags = Array.from(document.querySelectorAll('input[name="tag"]:checked')).map(cb => cb.value.toLowerCase());
        state.currentPage = 1;
        updateUI();
    }

    // 更新 UI
    function updateUI() {
        const visibleItems = [];

        // 筛选文章
        elements.postItems.forEach(item => {
            const title = item.dataset.title || '';
            const year = item.dataset.year || '';

            // 搜索匹配
            const searchMatch = !state.searchQuery || title.includes(state.searchQuery);

            // 分类匹配（直接用字符串包含匹配，支持多词分类名如 "Spring Cloud"）
            const categoryMatch = state.selectedCategories.length === 0 || 
                state.selectedCategories.some(cat => (item.dataset.categories || '').includes(cat));
            
            
            // 年份匹配
            const yearMatch = state.selectedYears.length === 0 || 
                state.selectedYears.includes(year);

            // 标签匹配（直接用字符串包含匹配，支持多词标签）
            const tagMatch = state.selectedTags.length === 0 || 
                state.selectedTags.some(t => (item.dataset.tags || '').includes(t));

            // 综合匹配
            const isVisible = searchMatch && categoryMatch && yearMatch && tagMatch;
            
            item.classList.toggle('hidden', !isVisible);

            if (isVisible) {
                visibleItems.push(item);
            }
        });

        // 更新无结果提示
        if (elements.noResults) {
            elements.noResults.style.display = visibleItems.length === 0 ? 'block' : 'none';
        }

        // 更新总数统计
        if (elements.totalCount) {
            elements.totalCount.textContent = visibleItems.length;
        }

        // 更新分页
        renderPagination(visibleItems.length);

        // 显示当前页的文章
        showCurrentPage(visibleItems);
    }

    // 渲染分页
    function renderPagination(totalItems) {
        if (!elements.pagination) return;

        const totalPages = Math.ceil(totalItems / CONFIG.itemsPerPage);
        
        if (totalPages <= 1) {
            elements.pagination.innerHTML = '';
            return;
        }

        let html = '';

        // 上一页
        html += `<button class="pagination-btn" ${state.currentPage === 1 ? 'disabled' : ''} data-page="${state.currentPage - 1}">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="15 18 9 12 15 6"></polyline>
            </svg>
        </button>`;

        // 页码
        const maxVisiblePages = 5;
        let startPage = Math.max(1, state.currentPage - Math.floor(maxVisiblePages / 2));
        let endPage = Math.min(totalPages, startPage + maxVisiblePages - 1);

        if (endPage - startPage < maxVisiblePages - 1) {
            startPage = Math.max(1, endPage - maxVisiblePages + 1);
        }

        if (startPage > 1) {
            html += `<button class="pagination-btn" data-page="1">1</button>`;
            if (startPage > 2) {
                html += `<span class="pagination-info">...</span>`;
            }
        }

        for (let i = startPage; i <= endPage; i++) {
            html += `<button class="pagination-btn ${i === state.currentPage ? 'active' : ''}" data-page="${i}">${i}</button>`;
        }

        if (endPage < totalPages) {
            if (endPage < totalPages - 1) {
                html += `<span class="pagination-info">...</span>`;
            }
            html += `<button class="pagination-btn" data-page="${totalPages}">${totalPages}</button>`;
        }

        // 下一页
        html += `<button class="pagination-btn" ${state.currentPage === totalPages ? 'disabled' : ''} data-page="${state.currentPage + 1}">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="9 18 15 12 9 6"></polyline>
            </svg>
        </button>`;

        // 总数信息
        html += `<span class="pagination-info">共 ${totalItems} 篇文章</span>`;

        elements.pagination.innerHTML = html;

        // 绑定分页点击事件
        elements.pagination.querySelectorAll('.pagination-btn[data-page]').forEach(btn => {
            btn.addEventListener('click', () => {
                const page = parseInt(btn.dataset.page);
                if (page && page !== state.currentPage) {
                    state.currentPage = page;
                    updateUI();
                    scrollToTop();
                }
            });
        });
    }

    // 显示当前页的文章
    function showCurrentPage(visibleItems) {
        const start = (state.currentPage - 1) * CONFIG.itemsPerPage;
        const end = start + CONFIG.itemsPerPage;

        visibleItems.forEach((item, index) => {
            item.classList.toggle('hidden', index < start || index >= end);
        });
    }

    // 滚动到顶部
    function scrollToTop() {
        const postsMain = document.querySelector('.posts-main');
        if (postsMain) {
            postsMain.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } else {
            window.scrollTo({ top: 0, behavior: 'smooth' });
        }
    }

    // 重置筛选
    function resetFilters() {
        // 重置状态
        state = {
            searchQuery: '',
            selectedCategories: [],
            selectedYears: [],
            selectedTags: [],
            currentPage: 1
        };

        // 重置搜索框
        if (elements.searchInput) {
            elements.searchInput.value = '';
        }

        // 重置复选框
        document.querySelectorAll('input[name="category"], input[name="year"], input[name="tag"]').forEach(checkbox => {
            checkbox.checked = false;
        });

        // 关闭移动端筛选
        if (elements.filters) {
            elements.filters.classList.remove('active');
        }

        // 更新 UI
        updateUI();
    }

    // 防抖函数
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    // DOM 加载完成后初始化
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
