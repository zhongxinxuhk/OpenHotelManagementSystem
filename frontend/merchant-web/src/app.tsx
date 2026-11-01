import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';

import {
  AvatarDropdown,
  ErrorBoundary,
  Footer,
  LangDropdown,
  OfflineBanner,
  VersionDropdown,
} from '@/components';
import HotelSelector from '@/components/HotelSelector';
import { authMe, type UserView } from '@/services/pms/auth';
import {
  clearSession,
  getToken,
  isSessionExpired,
} from '@/services/pms/session';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';

// Initialize dayjs plugins globally
dayjs.extend(relativeTime);

const _isDev = process.env.NODE_ENV === 'development';
const loginPath = '/user/login';

/**
 * 强制改密页。
 *
 * 不进主框架（`layout: false`）是有意的：顶栏的门店选择器会去拉 `/api/v1/hotels`，
 * 而待改密状态下所有业务接口都被服务端拒绝——人还没开始用就先看到一串报错，
 * 却不知道原因是"口令还没改"。
 */
const passwordPath = '/user/password';

/**
 * 应用初始状态（S3-01）。
 *
 * 关键变化：当前身份来自**后端会话**（`GET /api/v1/auth/me`），
 * 不是模板的 mock 接口。
 *
 * 为什么不缓存业务数据：只缓存"当前用户 + 权限点 + 可访问门店"。
 * 订单、房态这些业务数据随时间变化，缓存起来必然展示过期事实。
 */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: UserView;
  loading?: boolean;
  fetchUserInfo?: () => Promise<UserView | undefined>;
  settingDrawerOpen?: boolean;
}> {
  const fetchUserInfo = async () => {
    // 没有令牌或本地判定已过期：不发请求，直接回登录页。
    // 发一个注定 401 的请求只会让日志里堆满无意义的拒绝记录。
    if (!getToken() || isSessionExpired()) {
      clearSession();
      return undefined;
    }
    try {
      return await authMe();
    } catch (_error) {
      // 会话在服务端已失效（被登出、被撤权、被改密回收）。
      clearSession();
      const { pathname, search, hash } = history.location;
      history.replace(
        `${loginPath}?redirect=${encodeURIComponent(pathname + search + hash)}`,
      );
      return undefined;
    }
  };

  const { location } = history;
  const isLoginPage = [
    loginPath,
    '/user/register',
    '/user/register-result',
  ].includes(location.pathname);

  if (!isLoginPage) {
    const currentUser = await fetchUserInfo();
    return {
      fetchUserInfo,
      currentUser,
      settings: defaultSettings as Partial<LayoutSettings>,
      settingDrawerOpen: false,
    };
  }
  return {
    fetchUserInfo,
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  return {
    menuItemRender: (item, dom) => {
      if (item.path) {
        return (
          <Link to={item.path} prefetch>
            {dom}
          </Link>
        );
      }
      return dom;
    },
    actionsRender: () => {
      // `locale: false` opts out of the language switcher. ProLayout's own
      // `locale` prop is a locale string, so narrow to the boolean toggle here.
      const localeEnabled =
        (initialState?.settings as { locale?: boolean })?.locale !== false;
      return [
        // 门店切换器：所有业务接口都是 /api/v1/hotels/{hotelId}/... 形状，
        // 门店由顶栏统一切换，业务页只读当前值。
        <HotelSelector key="hotel" />,
        <VersionDropdown key="version" />,
        localeEnabled && <LangDropdown key="lang" />,
      ].filter(Boolean);
    },
    avatarProps: {
      title:
        initialState?.currentUser?.displayName ??
        initialState?.currentUser?.username,
      render: (_, avatarChildren) => (
        <AvatarDropdown>{avatarChildren}</AvatarDropdown>
      ),
    },
    footerRender: () => <Footer />,
    onPageChange: () => {
      const { location } = history;
      // 如果没有登录，重定向到 login
      if (!initialState?.currentUser && location.pathname !== loginPath) {
        history.replace(
          `${loginPath}?redirect=${encodeURIComponent(location.pathname + location.search + location.hash)}`,
        );
        return;
      }
      /*
       * 待改密：把人送到改密页。
       *
       * 服务端已经拦住了所有业务接口，这里只是别让人对着 403 猜原因。
       * 判断放在 `onPageChange` 而不是启动时一次：改完密之后同一个会话就解除限制了
       * （标记是每请求从库里读的），不需要重新登录，也就不能只在启动时判一次。
       */
      if (
        initialState?.currentUser?.mustChangePassword &&
        location.pathname !== passwordPath
      ) {
        history.replace(
          `${passwordPath}?redirect=${encodeURIComponent(location.pathname + location.search + location.hash)}`,
        );
      }
    },
    // Replace ProLayout's default ErrorBoundary with our offline-aware version,
    // so chunk load errors show friendly messages instead of "Something went wrong."
    ErrorBoundary,
    menuHeaderRender: undefined,
    // 增加一个 loading 的状态
    childrenRender: (children) => {
      return (
        <>
          {children}
          <SettingDrawer
            disableUrlParams
            enableDarkTheme
            collapse={initialState?.settingDrawerOpen}
            onCollapseChange={(open) => {
              setInitialState((s) => ({
                ...s,
                settingDrawerOpen: open,
              }));
            }}
            settings={initialState?.settings}
            onSettingChange={(settings) => {
              setInitialState((s) => ({
                ...s,
                settings,
              }));
            }}
          />
        </>
      );
    },
    ...initialState?.settings,
  };
};

/**
 * 请求配置：错误处理与令牌注入都在 requestErrorConfig 里收口。
 *
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  ...errorConfig,
};

export function rootContainer(container: React.ReactNode) {
  return (
    <>
      <OfflineBanner />
      <ErrorBoundary>{container}</ErrorBoundary>
    </>
  );
}
