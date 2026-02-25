{
  description = "ART19 MCP Server — Babashka Streamable HTTP MCP server for the ART19 Content API";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};
        bb = pkgs.babashka;

        art19-mcp = pkgs.stdenv.mkDerivation {
          name = "art19-mcp";
          version = "1.0.0";
          src = ./.;

          nativeBuildInputs = [bb];

          unpackPhase = "cp -r $src/* ./";
          buildPhase = "chmod +x art19_mcp.bb";
          installPhase = ''
                        mkdir -p $out/bin $out/share/art19-mcp
                        cp art19_mcp.bb $out/share/art19-mcp/
                        cat > $out/bin/art19-mcp << 'EOF'
            #!/usr/bin/env bash
            exec ${bb}/bin/bb ${placeholder "out"}/share/art19-mcp/art19_mcp.bb "$@"
            EOF
                        chmod +x $out/bin/art19-mcp
          '';

          meta = with pkgs.lib; {
            description = "ART19 MCP Server for Jupiter Broadcasting";
            homepage = "https://github.com/JupiterBroadcasting/J.O.E.";
            license = licenses.mit;
            platforms = platforms.linux;
          };
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          default = art19-mcp;
          inherit art19-mcp;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = [bb pkgs.clj-kondo pkgs.jq];
          shellHook = ''
            echo "art19-mcp dev shell"
            echo "  bb run      — start server (OS-assigned port)"
            echo "  bb start    — start server on port 3007"
            echo "  bb test     — run tests"
            echo "  bb lint     — clj-kondo lint"
            echo ""
            echo "Auth: set ART19_API_TOKEN and ART19_API_CREDENTIAL"
            echo "      or write ~/.config/art19/config.edn"
          '';
        };
      }
    )
    // {
      # NixOS module for use as a service in J.O.E. or any NixOS host
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }: let
        cfg = config.services.art19-mcp;
        bb = pkgs.babashka;
        # Build the package inline so the module is self-contained
        art19-mcp-pkg = pkgs.stdenv.mkDerivation {
          name = "art19-mcp";
          src = ./.;
          nativeBuildInputs = [bb];
          unpackPhase = "cp -r $src/* ./";
          buildPhase = "chmod +x art19_mcp.bb";
          installPhase = ''
                          mkdir -p $out/bin $out/share/art19-mcp
                          cp art19_mcp.bb $out/share/art19-mcp/
                          cat > $out/bin/art19-mcp << EOF
            #!/usr/bin/env bash
            exec ${bb}/bin/bb $out/share/art19-mcp/art19_mcp.bb "\$@"
            EOF
                          chmod +x $out/bin/art19-mcp
          '';
        };
      in {
        options.services.art19-mcp = {
          enable = lib.mkEnableOption "ART19 MCP Server";

          port = lib.mkOption {
            type = lib.types.int;
            default = 3007;
            description = "Port for the ART19 MCP server to listen on.";
          };

          tokenFile = lib.mkOption {
            type = lib.types.path;
            description = ''
              Path to a file containing ART19 credentials in the format:
                ART19_API_TOKEN=your-token
                ART19_API_CREDENTIAL=your-credential
              Keep this file outside the Nix store (e.g. /run/secrets/art19).
            '';
          };

          user = lib.mkOption {
            type = lib.types.str;
            default = "art19-mcp";
            description = "User to run the service as.";
          };

          group = lib.mkOption {
            type = lib.types.str;
            default = "art19-mcp";
            description = "Group to run the service as.";
          };
        };

        config = lib.mkIf cfg.enable {
          users.users.${cfg.user} = {
            isSystemUser = true;
            group = cfg.group;
            description = "ART19 MCP Server";
          };
          users.groups.${cfg.group} = {};

          systemd.services.art19-mcp = {
            description = "ART19 MCP Server";
            wantedBy = ["multi-user.target"];
            after = ["network.target"];

            serviceConfig = {
              Type = "simple";
              User = cfg.user;
              Group = cfg.group;
              ExecStart = "${art19-mcp-pkg}/bin/art19-mcp";
              Restart = "on-failure";
              RestartSec = "5s";

              # Load token/credential from secrets file
              EnvironmentFile = cfg.tokenFile;
              Environment = [
                "ART19_MCP_PORT=${toString cfg.port}"
              ];

              # Hardening
              NoNewPrivileges = true;
              PrivateTmp = true;
              ProtectSystem = "strict";
              ProtectHome = "read-only";
            };
          };
        };
      };
    };
}
