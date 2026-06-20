import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    redirect: '/containers',
    meta: { requiresAuth: true },
    children: [
      {
        path: '/containers',
        name: 'Containers',
        component: () => import('../views/Containers.vue')
      },
      {
        path: '/students',
        name: 'Students',
        component: () => import('../views/Students.vue')
      },
      {
        path: '/files',
        name: 'Files',
        component: () => import('../views/Files.vue')
      },
      {
        path: '/gpu',
        name: 'Gpu',
        component: () => import('../views/Gpu.vue')
      },
      {
        path: '/port-forward',
        name: 'PortForward',
        component: () => import('../views/PortForward.vue')
      },
      {
        path: '/image-snapshot',
        name: 'ImageSnapshot',
        component: () => import('../views/ImageSnapshot.vue')
      },
      {
        path: '/tasks',
        name: 'Tasks',
        component: () => import('../views/Tasks.vue')
      },
      {
        path: '/monitor',
        name: 'Monitor',
        component: () => import('../views/Monitor.vue')
      },
      {
        path: '/terminal',
        name: 'Terminal',
        component: () => import('../views/Terminal.vue')
      },
      {
        path: '/student-chat',
        name: 'StudentChat',
        component: () => import('../views/StudentChat.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 路由守卫：支持角色权限
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const role = localStorage.getItem('role') || 'ADMIN'

  // 未登录状态
  if (to.meta.requiresAuth !== false && !token) {
    next('/login')
    return
  }

  // 已登录用户访问登录页 → 跳转到默认页
  if (to.path === '/login' && token) {
    if (role === 'STUDENT') {
      next('/port-forward')
    } else {
      next('/')
    }
    return
  }

  // 学生用户访问非端口转发页 → 重定向到 /port-forward
  if (role === 'STUDENT' && to.path !== '/port-forward' && to.path !== '/student-chat' && to.path !== '/login') {
    next('/port-forward')
    return
  }

  next()
})

export default router
