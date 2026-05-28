import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/ws": {
        target: "ws://localhost:3007",
        ws: true,
      },
      "/api": {
        target: "http://localhost:3007",
        changeOrigin: true,
      },
      "/v3/api-docs": {
        target: "http://localhost:3007",
        changeOrigin: true,
      },
      "/swagger-ui.html": {
        target: "http://localhost:3007",
        changeOrigin: true,
      },
      "/swagger-ui": {
        target: "http://localhost:3007",
        changeOrigin: true,
      },
      "/webjars": {
        target: "http://localhost:3007",
        changeOrigin: true,
      },
      "/actuator": {
        target: "http://localhost:3007",
        changeOrigin: true,
      },
    },
  },
});
