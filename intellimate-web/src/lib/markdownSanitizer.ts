/**
 * 修复流式传输中不完整的 Markdown 语法，防止 react-markdown 渲染异常。
 */
export function sanitizePartialMarkdown(input: string): string {
  let text = input;

  const fenceMatches = text.match(/^```/gm);
  if (fenceMatches && fenceMatches.length % 2 !== 0) {
    text += "\n```";
    return text;
  }

  const outsideFence = removeCodeFences(text);

  const backtickCount = (outsideFence.match(/`/g) || []).length;
  if (backtickCount % 2 !== 0) {
    text += "`";
  }

  const boldMatches = outsideFence.match(/\*\*/g);
  if (boldMatches && boldMatches.length % 2 !== 0) {
    text += "**";
  }

  const afterBoldRemoval = outsideFence.replace(/\*\*/g, "");
  const italicMatches = afterBoldRemoval.match(/\*/g);
  if (italicMatches && italicMatches.length % 2 !== 0) {
    text += "*";
  }

  const strikeMatches = outsideFence.match(/~~/g);
  if (strikeMatches && strikeMatches.length % 2 !== 0) {
    text += "~~";
  }

  text = text.replace(/\[([^\]]*)\]\([^)]*$/, "[$1]()");

  const lastOpen = text.lastIndexOf("[");
  const lastClose = text.lastIndexOf("]");
  if (lastOpen > lastClose) {
    text = text.substring(0, lastOpen) + text.substring(lastOpen + 1);
  }

  return text;
}

function removeCodeFences(text: string): string {
  return text.replace(/```[\s\S]*?```/g, "");
}
