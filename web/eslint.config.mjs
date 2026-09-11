// 前端唯一的靜態檢查原本只有 tsc，而 tsc 不看 hook 的依賴陣列——
// LiveContext 的訂閱參考計數正好是「漏一個依賴就安靜地不對」的那種程式碼。
import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // forms.tsx 與 LiveContext.tsx 刻意把共用 hook 與元件放在一起（見 CLAUDE.md 的共用做法），
      // 所以留 warn 不擋建置。但它指到的是真的：熱更新這兩個檔案之後，
      // 畫面會噴「useLive 必須在 LiveProvider 內使用」直到手動重整。
      'react-refresh/only-export-components': 'warn',
      // `const { children: _children, ...rest } = x` 是用來省略欄位的，不是忘了用
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        ignoreRestSiblings: true,
      }],
    },
  },
);
