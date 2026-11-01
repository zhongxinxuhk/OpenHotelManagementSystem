import { Helmet } from '@umijs/max';
import { Alert, Typography } from 'antd';
import { createStyles } from 'antd-style';
import React from 'react';
import { Footer } from '@/components';
import Settings from '../../../../config/defaultSettings';

/**
 * 企业商旅登录占位（R-20261102-06）。
 *
 * **DEC-17 OPEN**：企业对公账户未定案——企业身份挂到哪个平面
 * （CORP_SSO 进 GUEST 平面，或独立 CORP 平面）没有结论。
 * 骨架期不提供登录表单：画一个能提交的表单等于假装功能存在
 * （前端规范 §7：呈现真实状态，不演）。
 */
const useStyles = createStyles(({ token }) => ({
  container: {
    display: 'flex',
    flexDirection: 'column',
    height: '100vh',
    overflow: 'auto',
    backgroundColor: token.colorBgLayout,
  },
  body: {
    flex: '1',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '32px 16px',
  },
  card: {
    width: '100%',
    maxWidth: 420,
    padding: 32,
    backgroundColor: token.colorBgContainer,
    borderRadius: token.borderRadiusLG,
    boxShadow: token.boxShadowTertiary,
  },
}));

const CorpLogin: React.FC = () => {
  const { styles } = useStyles();
  return (
    <div className={styles.container}>
      <Helmet>
        <title>登录 - {Settings.title}</title>
      </Helmet>
      <div className={styles.body}>
        <div className={styles.card}>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            {Settings.title}
          </Typography.Title>
          <Alert
            type="info"
            showIcon
            title="企业账户登录未开放"
            description="企业对公账户方案未定案（DEC-17：需先明确「企业 ≠ 租户」及身份归属平面）。定案并接通后端后开放登录。"
          />
        </div>
      </div>
      <Footer />
    </div>
  );
};

export default CorpLogin;
