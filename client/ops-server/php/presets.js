'use strict';
/*
 * Framework / application presets.
 *
 * Each preset decides three things that must agree or the site 404s / 502s:
 *   publicDir       – what `htdocs` symlinks to inside the release
 *   locationBlock   – the URI->script routing in nginx
 *   denyPhpIn       – writeable dirs where PHP execution must be refused
 *
 * `install` describes how an empty site is scaffolded (one-click catalogue).
 */

const PRESETS = {
  generic: {
    label: '通用 PHP 站点',
    publicDir: '',
    index: 'index.php index.html index.htm',
    locationBlock: `
    location / {
        try_files $uri $uri/ /index.php$is_args$args;
    }`,
    denyPhpIn: ['uploads', 'upload', 'files', 'tmp'],
    hint: '适合自研 PHP 项目：代码直接放在站点根目录。',
  },

  wordpress: {
    label: 'WordPress',
    publicDir: '',
    index: 'index.php index.html',
    locationBlock: `
    location / {
        try_files $uri $uri/ /index.php?$args;
    }`,
    denyPhpIn: ['wp-content/uploads', 'wp-content/cache', 'wp-content/upgrade'],
    hardening: `
    # xmlrpc 常被用于爆破，按需开启
    # location = /xmlrpc.php { deny all; }
    location ~* /wp-includes/.*\\.php$ { deny all; }`,
    tablePrefixDefault: 'wp_',
    install: {
      kind: 'download',
      name: 'wordpress',
      versionUrl: 'https://api.wordpress.org/core/version-check/1.7/?channel=stable',
      configTemplate: 'wp-config.php',
      postInstall: '需要浏览器访问站点完成 5 分钟安装向导（数据库已由面板写入 wp-config.php）。',
    },
    hint: '官方最新稳定版一键安装，自动写入 wp-config.php 与数据库。',
  },

  laravel: {
    label: 'Laravel',
    publicDir: 'public',
    index: 'index.php',
    locationBlock: `
    location / {
        try_files $uri $uri/ /index.php?$query_string;
    }`,
    denyPhpIn: ['storage', 'bootstrap/cache'],
    install: {
      kind: 'composer',
      name: 'laravel/laravel',
      args: '',
      postInstall: '面板已生成 .env；仍需执行 php artisan key:generate（部署时会自动跑）。',
    },
    envKeys: ['APP_KEY', 'APP_URL', 'APP_ENV', 'DB_DATABASE', 'DB_USERNAME', 'DB_PASSWORD'],
    hint: '文档根为 public/，依赖 composer，部署后自动 artisan migrate --force。',
  },

  thinkphp: {
    label: 'ThinkPHP 6/8',
    publicDir: 'public',
    index: 'index.php',
    locationBlock: `
    location / {
        if (!-e $request_filename) {
            rewrite ^(.*)$ /index.php?s=$1 last;
        }
    }`,
    denyPhpIn: ['runtime'],
    install: { kind: 'composer', name: 'topthink/think-skeleton', args: '' },
    envKeys: ['APP_DEBUG', 'DATABASE_HOSTNAME', 'DATABASE_DATABASE', 'DATABASE_USERNAME', 'DATABASE_PASSWORD'],
    hint: 'PATHINFO 模式 rewrite；runtime/ 禁止执行 PHP。',
  },

  codeigniter: {
    label: 'CodeIgniter 4',
    publicDir: 'public',
    index: 'index.php',
    locationBlock: `
    location / {
        try_files $uri $uri/ /index.php$uri?$query_string;
    }`,
    denyPhpIn: ['writable'],
    install: { kind: 'composer', name: 'codeigniter4/appstarter', args: '' },
    envKeys: ['CI_ENVIRONMENT', 'database.default.database', 'database.default.username', 'database.default.password'],
    hint: 'uri_protection 需为 None；writable/ 为运行期可写目录。',
  },

  symfony: {
    label: 'Symfony',
    publicDir: 'public',
    index: 'index.php',
    locationBlock: `
    location / {
        # Symfony 前端控制器模式：所有请求交给 public/index.php
        try_files $uri $uri/ /index.php$is_args$args;
    }`,
    denyPhpIn: ['var', 'var/cache'],
    install: { kind: 'composer', name: 'symfony/skeleton', args: '' },
    hint: '需要 Symfony Flex；public/index.php 为唯一入口。',
  },

  drupal: {
    label: 'Drupal',
    publicDir: 'web',
    index: 'index.php',
    locationBlock: `
    location = /favicon.ico { log_not_found off; access_log off; }
    location = /robots.txt { allow all; log_not_found off; access_log off; }
    location / {
        try_files $uri /index.php?$query_string;
    }`,
    denyPhpIn: ['sites/default/files'],
    install: { kind: 'composer', name: 'drupal/recommended-project', args: '' },
    hint: '文档根 web/，站点文件在 sites/default/files。',
  },

  hyperf: {
    label: 'Hyperf / Swoole（反向代理）',
    type: 'proxy',
    locationBlock: `
    location / {
        proxy_pass http://127.0.0.1:@PROXY_PORT@;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
    }`,
    install: { kind: 'composer', name: 'hyperf/hyperf-skeleton', args: '' },
    hint: '常驻内存框架：不走 PHP-FPM，面板按 systemd 服务看护进程。',
  },

  static: {
    label: '纯静态站点',
    noPhp: true,
    index: 'index.html index.htm',
    locationBlock: `
    location / {
        try_files $uri $uri/ /index.html;
    }`,
    denyPhpIn: [],
    hint: '不解析 PHP，SPA 回落到 index.html。',
  },

  custom: {
    label: '自定义（手写 location 块）',
    locationBlock: null,
    denyPhpIn: [],
    hint: '在「路由规则」里自己写 location 块，面板原样注入。',
  },
};

const PHP_BLOCK = (sock, extra) => `
    location ~ \\.php(/|$) {
        try_files $uri =404;
        fastcgi_split_path_info ^(.+\\.php)(/.*)$;
        fastcgi_pass unix:${sock};
        fastcgi_index index.php;
        include fastcgi_params;
        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
        fastcgi_param DOCUMENT_ROOT $realpath_root;
        fastcgi_param PATH_INFO $fastcgi_path_info;
        fastcgi_read_timeout ${extra && extra.readTimeout ? extra.readTimeout : 60}s;
        fastcgi_buffer_size 16k;
        fastcgi_buffers 16 16k;
        fastcgi_busy_buffers_size 32k;
    }`;

/** Extension-based PHP handler for presets that must not use PATH_INFO. */
const PHP_BLOCK_SIMPLE = (sock, extra) => `
    location ~ \\.php$ {
        try_files $uri =404;
        fastcgi_pass unix:${sock};
        fastcgi_index index.php;
        include fastcgi_params;
        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
        fastcgi_param DOCUMENT_ROOT $realpath_root;
        fastcgi_read_timeout ${extra && extra.readTimeout ? extra.readTimeout : 60}s;
        fastcgi_buffer_size 16k;
        fastcgi_buffers 16 16k;
        fastcgi_busy_buffers_size 32k;
    }`;

/* `expires` emits both Expires and Cache-Control itself. Deliberately no
   add_header here: an add_header inside a location stops the server-level
   security headers from being inherited into it. */
const STATIC_CACHE = `
    location ~* \\.(?:jpg|jpeg|gif|png|apng|webp|avif|ico|svg|css|js|mjs|map|woff|woff2|ttf|eot|otf|mp4|webm)$ {
        expires 30d;
        access_log off;
        log_not_found off;
    }`;

const DOTFILE_GUARD = `
    # 隐藏文件与版本库目录一律拒绝（.well-known/acme-challenge 例外，签证书要用）
    location ~ /\\.(?!well-known) {
        deny all;
        access_log off;
        log_not_found off;
    }`;

const SECURITY_HEADERS = `
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;`;

function presetOf(name) {
  return PRESETS[name] || PRESETS.generic;
}

function list() {
  return Object.keys(PRESETS).map((k) => ({
    id: k,
    label: PRESETS[k].label,
    publicDir: PRESETS[k].publicDir || '',
    type: PRESETS[k].type || 'php',
    noPhp: !!PRESETS[k].noPhp,
    install: PRESETS[k].install ? { kind: PRESETS[k].install.kind, name: PRESETS[k].install.name } : null,
    hint: PRESETS[k].hint || '',
  }));
}

module.exports = {
  PRESETS, PHP_BLOCK, PHP_BLOCK_SIMPLE, STATIC_CACHE, DOTFILE_GUARD, SECURITY_HEADERS,
  presetOf, list,
};
