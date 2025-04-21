const javaParser = {
  extractEndpoints: (content) => {
    const endpointRegex = /if\s*\((command\.equalsIgnoreCase\("([^"]+)"\))/g;
    const endpoints = [];
    let match;

    while ((match = endpointRegex.exec(content)) !== null) {
      endpoints.push(match[2]);
    }

    return [...new Set(endpoints)];
  },

  extractParameters: (content, endpoint) => {
    const paramRegex = new RegExp(
      `command\\.equalsIgnoreCase\\("${endpoint}"\\)[\\s\\S]*?request\\.getParameter\\(["']([^"']+)["']\\)`,
      "g"
    );

    const params = [];
    let paramMatch;

    while ((paramMatch = paramRegex.exec(content)) !== null) {
      params.push(paramMatch[1]);
    }

    return [...new Set(params)];
  },

  extractMethod: (content, endpoint) => {
    const methodRegex = new RegExp(
      `(doGet|doPost).*?command\\.equalsIgnoreCase\\("${endpoint}"\\)`,
      "s"
    );
    return methodRegex.test(content) ? "get" : "post";
  },
};

module.exports = javaParser;
