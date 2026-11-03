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
import {
  getGuestMe,
  type GuestMeView,
} from '@/services/pms/auth';
import {
  clearSession,
  getToken,
  isSessionExpired,
} from '@/services/pms/session';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';

// Initialize dayjs plugins globally
dayjs.extend(relativeTime);

const loginPath = '/user/login';

/**
 * 匿名可用的路径（R-20261103-02）：房型浏览是公开面（/api/v1/public/**）的
 * 消费页——住客在注册前就要能选房，把它挡在登录后面等于公开面白开。
 * 列表外的页面（我的/订单/绑定）仍然要求登录。
 */
const ANONYMOUS_PATHS = ['/home'];
// 住客平面没有强制改密机制（无 must_change_password 标记、无改密接口）：
// 口令找回走「忘记密码」流程（规划中，复用验证码通道）。

/**
 * 应用初始状态 —— 住客版（R-20261102-05）。
 *
 * 当前身份来自**后端会话**（`GET /api/v1/guest/me`），与 merchant-web 同一模式。
 * 住客身份只有：显示名、实名状态、会话过期——没有租户、门店、权限点
 * （GUEST 平面，ADR-019 §2.4）。
 */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: GuestMeView;
  loading?: boolean;
  fetchUserInfo?: () => Promise<GuestMeView | undefined>;
  settingDrawerOpen?: boolean;
}> {
  const fetchUserInfo = async () => {
    // 没有令牌或本地判定已过期：不发请求，直接回登录页。
    if (!getToken() || isSessionExpired()) {
      clearSession();
      return undefined;
    }
    try {
      return await getGuestMe();
    } catch (_error) {
      // 会话在服务端已失效（被登出、过期）。
      clearSession();
      const { pathname, search, hash } = history.location;
      history.replace(
        `${loginPath}?redirect=${encodeURIComponent(pathname + search + hash)}`,
      );
      return undefined;
    }
  };

  const { location } = history;
  const isLoginPage = [loginPath, '/user/register', '/user/register-result']
    .includes(location.pathname);

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
      // 平台端没有门店选择器：门店（hotel scope）是租户平面的概念，
      // 平台动作不按门店划分（ADR-019 §2.4）。
      return [
        <VersionDropdown key="version" />,
        localeEnabled && <LangDropdown key="lang" />,
      ].filter(Boolean);
    },
    avatarProps: {
      title: initialState?.currentUser?.displayName ?? '住客',
      render: (_, avatarChildren) => (
        <AvatarDropdown>{avatarChildren}</AvatarDropdown>
      ),
    },
    footerRender: () => <Footer />,
    onPageChange: () => {
      const { location } = history;
      // 未登录 → 回登录页；匿名路径（房型浏览）除外
      if (
        !initialState?.currentUser &&
        location.pathname !== loginPath &&
        !ANONYMOUS_PATHS.some((prefix) => location.pathname.startsWith(prefix))
      ) {
        history.replace(
          `${loginPath}?redirect=${encodeURIComponent(location.pathname + location.search + location.hash)}`,
        );
        return;
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
