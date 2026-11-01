import packageJson from '@root/package.json';
import { createStyles } from 'antd-style';
import React from 'react';

/**
 * 页脚（S3-01）。
 *
 * 按前端规范 §7.2：**不写版权宣传**，只保留版本号与构建信息。
 * 模板原版这里写着 "Ant Design Pro © {year}" 与一排第三方框架链接，
 * 对 PMS 使用者没有任何意义，还会让系统看起来像未完成的模板。
 */
const COMMIT_HASH = process.env.COMMIT_HASH || '';

const useStyles = createStyles(({ token, css }) => ({
  footer: css`
    padding: 16px 24px;
    text-align: center;
    color: ${token.colorTextDescription};
    font-size: ${token.fontSizeSM}px;
    line-height: ${token.lineHeight};
    background: transparent;
  `,
  meta: css`
    display: flex;
    align-items: center;
    justify-content: center;
    flex-wrap: wrap;
    gap: 4px 12px;
    font-family: ${token.fontFamilyCode};
    font-size: ${token.fontSizeSM - 1}px;
  `,
  label: css`
    color: ${token.colorTextQuaternary};
  `,
}));

const Footer: React.FC = () => {
  const { styles } = useStyles();

  return (
    <div className={styles.footer}>
      <div className={styles.meta}>
        <span className={styles.label}>版本</span>
        <span>{packageJson.version}</span>
        {COMMIT_HASH && (
          <>
            <span className={styles.label}>构建</span>
            <span>{COMMIT_HASH.slice(0, 7)}</span>
          </>
        )}
      </div>
    </div>
  );
};

export default Footer;
