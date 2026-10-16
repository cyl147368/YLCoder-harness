"""安装用户级运行文件；不读取或覆盖已有配置，不执行模型请求。"""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


def install(install_dir, config_dir):
    jar = ROOT / 'lib/ylc.jar'
    launchers = ROOT / 'bin'
    if not jar.is_file():
        version = ET.parse(ROOT / 'pom.xml').getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
        if not version or any(character not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-' for character in version):
            raise ValueError('pom.xml 中的项目版本无效')
        jar = ROOT / f'harness-cli/target/harness-cli-{version}-all.jar'
        launchers = ROOT / 'packaging/bin'
    template = ROOT / 'config.example.yaml'
    sources = {Path('lib/ylc.jar'): jar, Path('bin/ylc'): launchers / 'ylc',
               Path('bin/ylc.cmd'): launchers / 'ylc.cmd', Path('config.example.yaml'): template}
    if not all(path.is_file() for path in sources.values()):
        raise ValueError('缺少安装文件；源码用户请先运行 mvn -B verify，便携包用户请完整解压')
    install_dir, config_dir = install_dir.expanduser().resolve(), config_dir.expanduser().resolve()
    for name, source in sources.items():
        target = install_dir / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.resolve() != target.resolve():
            shutil.copyfile(source, target)
    if os.name != 'nt':
        (install_dir / 'bin/ylc').chmod(0o755)
    config_dir.mkdir(parents=True, exist_ok=True)
    config = config_dir / 'config.yaml'
    try:
        descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print(f'已保留现有配置：{config}')
    else:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(template.read_bytes())
        print(f'已创建配置：{config}')
    print(f'安装位置：{install_dir}')
    print(f'请编辑配置：{config}')
    if os.name == 'nt':
        print(f'请将 {install_dir / "bin"} 加入用户 PATH；Windows 推荐使用 install.ps1 自动设置。')
    else:
        print('将下行加入 ~/.profile（或你的 shell 配置），并在当前终端执行一次：')
        print(f'export PATH={shlex.quote(str(install_dir / "bin"))}:"$PATH"')
    print('随后在任意项目目录执行 ylc；默认处理当前目录。')


def main():
    default = Path(os.environ.get('LOCALAPPDATA', str(Path.home() / 'AppData/Local'))) / 'YLCoder-harness' if os.name == 'nt' else Path.home() / '.local/share/YLCoder-harness'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install-dir', type=Path, default=default, help='运行文件安装目录')
    parser.add_argument('--config-dir', type=Path, default=Path.home() / '.code-harness', help='配置模板目录；自定义时启动需使用 --config')
    args = parser.parse_args()
    install(args.install_dir, args.config_dir)


if __name__ == '__main__':
    main()
