interface ErrorMapping {
  pattern: RegExp;
  message: string;
  suggestion: string;
}

const ERROR_MAPPINGS: ErrorMapping[] = [
  {
    pattern: /timeout|timed?\s*out|ETIMEDOUT/i,
    message: "请求超时",
    suggestion: "服务器响应时间过长，请稍后重试或简化问题。",
  },
  {
    pattern: /network\s*error|fetch\s*failed|ERR_CONNECTION|ECONNREFUSED/i,
    message: "网络连接异常",
    suggestion: "请检查网络连接后重试。",
  },
  {
    pattern: /500|internal\s*server\s*error/i,
    message: "服务器内部错误",
    suggestion: "服务器遇到了问题，请稍后重试。",
  },
  {
    pattern: /502|bad\s*gateway/i,
    message: "服务网关错误",
    suggestion: "服务正在重启，请稍等几秒后重试。",
  },
  {
    pattern: /503|service\s*unavailable/i,
    message: "服务暂时不可用",
    suggestion: "服务负载过高或正在维护，请稍后重试。",
  },
  {
    pattern: /429|too\s*many\s*requests|rate\s*limit/i,
    message: "请求过于频繁",
    suggestion: "已超出速率限制，请稍等片刻再尝试。",
  },
  {
    pattern: /401|unauthorized|auth/i,
    message: "认证失败",
    suggestion: "请检查登录状态或刷新页面重新认证。",
  },
  {
    pattern: /403|forbidden|permission/i,
    message: "权限不足",
    suggestion: "当前操作需要更高权限，请联系管理员。",
  },
  {
    pattern: /404|not\s*found/i,
    message: "资源未找到",
    suggestion: "请求的接口或资源不存在，请检查配置。",
  },
  {
    pattern: /model.*unavailable|model.*not\s*found|no\s*available\s*model/i,
    message: "模型不可用",
    suggestion: "当前模型暂时无法使用，请尝试切换模型或稍后重试。",
  },
  {
    pattern: /token.*limit|context.*length|too\s*long/i,
    message: "消息过长",
    suggestion: "输入内容超出模型上下文限制，请尝试缩短消息或开始新对话。",
  },
];

export interface FriendlyError {
  title: string;
  suggestion: string;
  rawError: string;
}

export function toFriendlyError(rawError: string): FriendlyError {
  for (const mapping of ERROR_MAPPINGS) {
    if (mapping.pattern.test(rawError)) {
      return {
        title: mapping.message,
        suggestion: mapping.suggestion,
        rawError,
      };
    }
  }

  return {
    title: "发生了错误",
    suggestion: "请稍后重试，如果问题持续存在请联系管理员。",
    rawError,
  };
}
