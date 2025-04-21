const javaParser = require("./parser");

const generateSpec = (endpoints, javaCode) => {
  const paths = {};

  endpoints.forEach((endpoint) => {
    const parameters = javaParser
      .extractParameters(javaCode, endpoint)
      .filter((p) => p !== "command")
      .map((param) => ({
        name: param,
        in: "query",
        schema: { type: "string" },
        description: `Paramètre détecté pour ${endpoint}`,
      }));

    const method = javaParser.extractMethod(javaCode, endpoint);

    const pathItem = {
      [method]: {
        summary: `Endpoint: ${endpoint}`,
        description: `Endpoint détecté dans le code source`,
        operationId: endpoint.replace(/[^a-zA-Z0-9_]/g, "_"),
        parameters: [
          {
            name: "command",
            in: "query",
            required: true,
            schema: {
              type: "string",
              enum: [endpoint],
              example: endpoint,
            },
            description: "Commande principale",
          },
          ...parameters,
        ],
        responses: {
          200: {
            description: "Réponse standard",
            content: {
              "application/json": {
                schema: {
                  type: "object",
                  properties: {
                    result: { type: "string" },
                    message: { type: "string" },
                  },
                },
              },
            },
          },
          401: {
            description: "Non autorisé",
            content: {
              "application/json": { schema: { type: "object" } },
            },
          },
        },
      },
    };

    paths[
      `/${endpoint
        .replace(/[A-Z]/g, (m) => "-" + m.toLowerCase())
        .replace(/^get-?/, "")}`
    ] = pathItem;
  });

  return {
    openapi: "3.0.3",
    info: {
      title: "Documentation API XQual",
      version: "1.0.0",
      description: "Généré automatiquement à partir de CServer.java",
    },
    servers: [{ url: "/xapiserver" }],
    paths,
  };
};

module.exports = { generateSpec };
