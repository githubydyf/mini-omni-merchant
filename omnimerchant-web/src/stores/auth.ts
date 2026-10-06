/* ============================================================
 * 当前开发阶段：登录与鉴权已整体下线
 *
 * 本文件原实现（JWT 解析、登录、刷新令牌、退出登录、租户权限判定）
 * 全部保留在文件底部的注释块中。
 * 目前仅导出一个惰性占位 store，保证仍引用它的页面（LoginView）
 * 能通过类型检查；该占位不会解析令牌、也不会发起任何认证请求。
 *
 * 恢复鉴权时：
 *   1. 删除下方 useAuthStore 占位实现；
 *   2. 取消文件底部注释块的注释，恢复原实现；
 *   3. 恢复路由守卫  src/router/index.ts；
 *   4. 恢复请求/响应拦截器  src/api/index.ts。
 * ============================================================ */

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type AuthClaims = {
  exp?: number
  sub?: string
  userId?: number
  role?: string
  roles?: string[]
  tenantIds?: number[]
  platformAdmin?: boolean
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const refreshToken = ref('')
  const email = ref('')
  const userId = computed(() => null)
  const roles = computed<string[]>(() => [])
  const tenantIds = computed<number[]>(() => [])
  const platformAdmin = computed(() => false)
  const isLoggedIn = computed(() => false)

  async function login(_email?: string, _password?: string): Promise<never> {
    throw new Error('登录与鉴权已下线，请直接访问 /admin 或 /chat')
  }

  async function refreshAccessToken() {
    return false
  }

  async function logout(_requestServer = true) {
    // 占位实现：无本地令牌需要清理。
  }

  return {
    token,
    refreshToken,
    email,
    userId,
    roles,
    tenantIds,
    platformAdmin,
    isLoggedIn,
    login,
    refreshAccessToken,
    logout,
  }
})

/* ============================================================
 * 原实现（已停用，恢复鉴权时取消本块注释）
 * ============================================================
 *
 * import { computed, ref } from 'vue'
 * import { defineStore } from 'pinia'
 * import api from '@/api'
 * import { setStoredTenantId } from '@/utils/tenant'
 *
 * const ACCESS_TOKEN_KEY = 'omni_access_token'
 * const REFRESH_TOKEN_KEY = 'omni_refresh_token'
 * const EMAIL_KEY = 'omni_email'
 *
 * type AuthTokenPayload = {
 *   token?: string
 *   accessToken?: string
 *   refreshToken?: string
 *   email?: string
 * }
 *
 * function parseJwtClaims(rawToken: string): AuthClaims | null {
 *   try {
 *     const payload = rawToken.split('.')[1]
 *     if (!payload) return null
 *     const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
 *     const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), '=')
 *     const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0))
 *     return JSON.parse(new TextDecoder().decode(bytes))
 *   } catch {
 *     return null
 *   }
 * }
 *
 * function isCurrentAuthToken(rawToken: string) {
 *   const claims = parseJwtClaims(rawToken)
 *   if (!claims || claims.role === 'WIDGET_CUSTOMER') return false
 *   if (typeof claims.exp === 'number' && claims.exp * 1000 <= Date.now()) return false
 *   return claims.platformAdmin === true || Boolean(claims.tenantIds?.length)
 * }
 *
 * function migrateLegacySession() {
 *   const legacyToken = localStorage.getItem('token') || ''
 *   if (!sessionStorage.getItem(ACCESS_TOKEN_KEY) && isCurrentAuthToken(legacyToken)) {
 *     sessionStorage.setItem(ACCESS_TOKEN_KEY, legacyToken)
 *     sessionStorage.setItem(EMAIL_KEY, localStorage.getItem('email') || '')
 *   }
 *   localStorage.removeItem('token')
 *   localStorage.removeItem('email')
 * }
 *
 * export const useAuthStore = defineStore('auth', () => {
 *   migrateLegacySession()
 *   const storedToken = sessionStorage.getItem(ACCESS_TOKEN_KEY) || ''
 *   const token = ref(isCurrentAuthToken(storedToken) ? storedToken : '')
 *   const refreshToken = ref(token.value ? sessionStorage.getItem(REFRESH_TOKEN_KEY) || '' : '')
 *   const email = ref(token.value ? sessionStorage.getItem(EMAIL_KEY) || '' : '')
 *   const claims = computed(() => parseJwtClaims(token.value))
 *   const userId = computed(() => claims.value?.userId || null)
 *   const roles = computed(() => claims.value?.roles || (claims.value?.role ? [claims.value.role] : []))
 *   const tenantIds = computed(() => claims.value?.tenantIds || [])
 *   const platformAdmin = computed(() => claims.value?.platformAdmin === true)
 *   const isLoggedIn = computed(() => isCurrentAuthToken(token.value))
 *
 *   function persistAuth(data: AuthTokenPayload) {
 *     const accessToken = data?.accessToken || data?.token || ''
 *     if (!isCurrentAuthToken(accessToken)) throw new Error('服务返回的登录令牌无有效租户权限')
 *     token.value = accessToken
 *     refreshToken.value = data?.refreshToken || refreshToken.value
 *     email.value = data?.email || parseJwtClaims(accessToken)?.sub || email.value
 *     sessionStorage.setItem(ACCESS_TOKEN_KEY, token.value)
 *     sessionStorage.setItem(EMAIL_KEY, email.value)
 *     if (refreshToken.value) sessionStorage.setItem(REFRESH_TOKEN_KEY, refreshToken.value)
 *   }
 *
 *   async function login(loginEmail: string, password: string) {
 *     const res = await api.post('/auth/login', { email: loginEmail, password })
 *     persistAuth(res.data)
 *     setStoredTenantId(null)
 *     return res.data
 *   }
 *
 *   async function refreshAccessToken() {
 *     if (!refreshToken.value) return false
 *     try {
 *       const response = await fetch('/api/auth/refresh', {
 *         method: 'POST',
 *         headers: { 'Content-Type': 'application/json' },
 *         body: JSON.stringify({ refreshToken: refreshToken.value }),
 *       })
 *       const body = await response.json()
 *       if (!response.ok || body?.code !== '200') return false
 *       persistAuth(body.data)
 *       return true
 *     } catch {
 *       return false
 *     }
 *   }
 *
 *   async function logout(requestServer = true) {
 *     const accessToken = token.value
 *     const currentRefreshToken = refreshToken.value
 *     token.value = ''
 *     refreshToken.value = ''
 *     email.value = ''
 *     sessionStorage.removeItem(ACCESS_TOKEN_KEY)
 *     sessionStorage.removeItem(REFRESH_TOKEN_KEY)
 *     sessionStorage.removeItem(EMAIL_KEY)
 *     setStoredTenantId(null)
 *     if (requestServer && accessToken) {
 *       try {
 *         await fetch('/api/auth/logout', {
 *           method: 'POST',
 *           headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
 *           body: JSON.stringify({ refreshToken: currentRefreshToken || null }),
 *         })
 *       } catch {
 *         // Local logout must still complete when the backend is unavailable.
 *       }
 *     }
 *   }
 *
 *   if (!isLoggedIn.value && storedToken) void logout(false)
 *
 *   return {
 *     token,
 *     refreshToken,
 *     email,
 *     userId,
 *     roles,
 *     tenantIds,
 *     platformAdmin,
 *     isLoggedIn,
 *     login,
 *     refreshAccessToken,
 *     logout,
 *   }
 * })
 * ============================================================ */
