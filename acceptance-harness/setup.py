from setuptools import find_namespace_packages, setup


setup(
    name="cli-anything-steve-agent",
    version="0.1.0",
    description="Real Forge client acceptance harness for Steve Industrial Agent",
    packages=find_namespace_packages(include=["cli_anything.*"]),
    install_requires=["click>=8.1.7", "prompt-toolkit>=3.0.43"],
    extras_require={"test": ["pytest>=8.0"]},
    entry_points={
        "console_scripts": [
            "cli-anything-steve-agent=cli_anything.steve_agent.steve_agent_cli:main",
            "steve-agent-test=cli_anything.steve_agent.steve_agent_cli:main",
        ],
    },
    package_data={"cli_anything.steve_agent": ["skills/*.md"]},
    data_files=[(
        "share/cli-anything-steve-agent/licenses",
        ["THIRD_PARTY_LICENSES/CLI-Anything-Apache-2.0.txt"],
    )],
    python_requires=">=3.10",
)
